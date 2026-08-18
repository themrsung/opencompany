# The conversion worker: headless LibreOffice, Korean fonts, and a Node runtime
# for mdv rendering. The heaviest component in the deployment, and the only
# place the stack is deliberately not Java 8 (ADR 0008).
#
# Restartable independently of everything else: a wedged worker degrades
# exports, it does not take the intranet down.

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
      '       browser as a webfont and registered into mdv pdf.fonts. -->' \
      '  <dir>/var/lib/coreintra/fonts</dir>' \
      '</fontconfig>' > /etc/fonts/conf.d/99-coreintra.conf

WORKDIR /app
COPY ops/conversion-worker/ /app/

# Never runs as root: this process opens documents uploaded by users, and
# LibreOffice's import filters are a large attack surface.
RUN useradd --system --create-home --uid 10001 worker \
    && chown -R worker:worker /app /var/lib/coreintra
USER worker
ENV HOME=/home/worker

EXPOSE 3000
CMD ["node", "server.mjs"]
