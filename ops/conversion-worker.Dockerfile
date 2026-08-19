# The conversion worker: headless LibreOffice, Korean fonts, and a Node runtime
# for mdv rendering. The heaviest component in the deployment, and the only
# place the stack is deliberately not Java 8 (ADR 0008).
#
# Restartable independently of everything else: a wedged worker degrades
# exports, it does not take the intranet down.

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: build the vendored mdv workspace
# ─────────────────────────────────────────────────────────────────────────────
#
# mdv is unpublished (version 0.0.0, `private: true`) and pinned as a git
# submodule, so there is no registry to install it from and the image has to
# build it (ADR 0008). It is built HERE, in a throwaway stage, from a COPY of
# the submodule - never in the checkout. `vendor/mdv/CONTRACTS.md` §1.2 forbids
# installing into the working tree, and two agents running `pnpm install` in one
# lockfile corrupt it; a layer in a builder stage is neither of those things.
#
# Only `packages/**` is built. The root `build` script also builds `apps/*` (the
# editor and the VS Code extension), which pulls esbuild and its platform binary
# for an artefact this container never serves. `--filter "./packages/**"` is the
# same form the upstream `perf` script uses, so it is a path they keep working.
FROM node:22-bookworm-slim AS mdv-build

# The version in mdv's own `packageManager` field. Pinned rather than "latest",
# because the lockfile is `--frozen-lockfile` and a different pnpm major reads
# it differently.
RUN corepack enable && corepack prepare pnpm@11.18.0 --activate

WORKDIR /opt/mdv
COPY vendor/mdv/ /opt/mdv/

# The pinned commit, which ADR 0008 makes part of the approval trail: a stored
# render has to be able to answer "which mdv drew this" years later, from the
# metadata alone. Three sources, in descending order of trust:
#
#   1. `--build-arg MDV_COMMIT=$(git -C vendor/mdv rev-parse HEAD)`, which is
#      what CI passes.
#   2. The submodule's detached HEAD out of the build context. The `HEA[D]`
#      glob is deliberate: a COPY of a literal missing path fails the build,
#      a wildcard matching nothing does not, so this stays optional for builds
#      from a source tarball. `docker compose build` passes no build args, so
#      without this the everyday build would record "unknown".
#   3. A digest of the sources actually built. Not a commit, and labelled
#      `tree:` so nobody mistakes it for one - but it identifies the bytes,
#      which is the property the trail actually needs.
#
# The SHA test is written as nested `if`s rather than `case ... && pin=$head`.
# Under `set -e` in dash - which is `/bin/sh` in this image, and is not the
# shell you get testing the same line on a Mac - a trailing `&&` whose left
# side is false fails the whole RUN. A HEAD holding a branch ref instead of a
# detached SHA would then abort the image build rather than falling through to
# the tree digest, which is the one case the fallback exists for.
ARG MDV_COMMIT=""
COPY .git/modules/vendor/mdv/HEA[D] /tmp/mdv-head
RUN set -eu; \
    pin="${MDV_COMMIT}"; \
    if [ -z "$pin" ] && [ -f /tmp/mdv-head ]; then \
      head="$(tr -d ' \t\n\r' < /tmp/mdv-head)"; \
      if [ "${#head}" = 40 ]; then \
        case "$head" in \
          *[!0-9a-f]*) : ;; \
          *) pin="$head" ;; \
        esac; \
      fi; \
    fi; \
    if [ -z "$pin" ]; then \
      pin="tree:$(find /opt/mdv/packages -type f \( -name '*.ts' -o -name '*.json' \) \
        -not -path '*/node_modules/*' | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-64)"; \
    fi; \
    printf '%s' "$pin" > /opt/mdv/PIN; \
    echo "mdv pin: $pin"

RUN pnpm install --frozen-lockfile \
    && pnpm -r --workspace-concurrency=1 --filter "./packages/**" build

# Fail the build rather than ship an image whose renderer is quietly missing.
# Without this the worker would come up, report `mdv.available: false` on
# /health, and refuse every render - correct behaviour for a broken image, but
# a broken image nobody noticed building.
RUN test -f /opt/mdv/packages/cli/dist/index.js \
    || (echo 'mdv build produced no CLI dist' >&2; exit 1)

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: the worker
# ─────────────────────────────────────────────────────────────────────────────
FROM node:22-bookworm-slim

# libreoffice-writer, not libreoffice-core alone. Learned the hard way while
# building this: core without writer installs a `soffice` that reports a
# version happily and then refuses every document with "no export filter found".
RUN apt-get update && apt-get install -y --no-install-recommends \
      libreoffice-writer \
      libreoffice-core \
      fontconfig \
      # Noto CJK is the FALLBACK chain, not the default face. Pretendard is
      # copied in below and leads the chain (docs/documents/fonts.md).
      fonts-noto-cjk \
      fonts-noto-cjk-extra \
      fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*

# Pretendard (SIL OFL 1.1) - bundled deliberately: free, redistributable and
# commercially usable, so it ships in both the managed and on-prem builds with
# no per-client licence conversation. Its Korean and Latin are designed
# together, so mixed Korean/Latin lines do not ransom-note.
#
# Hancom's Hamchorom faces are NEVER added here. A client who owns the licence
# installs them through the font manager under their own acknowledgement.
COPY ops/fonts/bundled/ /usr/share/fonts/truetype/coreintra/
RUN fc-cache -f

# Client-uploaded fonts arrive on a shared volume, mounted read-only: the
# worker consumes fonts, it never installs them. Installation goes through the
# API so it is audited and acknowledged.
RUN mkdir -p /etc/fonts/conf.d /var/lib/coreintra/fonts \
    && printf '%s\n' \
      '<?xml version="1.0"?>' \
      '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">' \
      '<fontconfig>' \
      '  <!-- One font store, three consumers: this same path is served to the' \
      '       browser as a webfont and is meant to feed mdv pdf.fonts. The' \
      '       third consumer is inert against the pinned mdv, which embeds no' \
      '       fonts at all - docs/documents/fonts.md. LibreOffice reads this' \
      '       directory and is what actually renders Korean today. -->' \
      '  <dir>/var/lib/coreintra/fonts</dir>' \
      '</fontconfig>' > /etc/fonts/conf.d/99-coreintra.conf

WORKDIR /app
COPY ops/conversion-worker/ /app/

# The built mdv, at the same absolute path it was built at. pnpm links workspace
# packages with RELATIVE symlinks into `node_modules/.pnpm`, so the tree moves
# intact only if its internal shape is preserved - copying it to a different
# path would still resolve, but keeping the path identical means a stack trace
# from the container matches a stack trace from the builder.
COPY --from=mdv-build /opt/mdv /opt/mdv
ENV COREINTRA_MDV_CLI=/opt/mdv/packages/cli/dist/index.js

# Read by the worker when COREINTRA_MDV_COMMIT is not set in the environment.
# It is a file rather than an ENV because the value is computed during the build
# and ENV cannot be assigned from a RUN.
ENV COREINTRA_MDV_PIN_FILE=/opt/mdv/PIN

# Never runs as root: this process opens documents uploaded by users, and
# LibreOffice's import filters are a large attack surface.
RUN useradd --system --create-home --uid 10001 worker \
    && chown -R worker:worker /app /var/lib/coreintra
USER worker
ENV HOME=/home/worker

EXPOSE 3000
CMD ["node", "server.mjs"]
