# Fonts

Fonts are not a detail of this system. The conversion container's installed font
set determines what every exported document looks like, and the failure mode is
silent.

## The problem, measured

On a stock container with no Korean font installed, fontconfig does not fail. It
guesses, and says nothing:

```
$ fc-match "Pretendard"        →  DejaVu Sans      # no Korean coverage at all
$ fc-match "함초롬바탕"          →  DejaVu Sans
$ fc-match ":lang=ko"          →  WenQuanYi Zen Hei # a *Chinese* face
```

A 지출결의서 rendered that way is not obviously broken. It is subtly wrong —
Korean set in a Chinese face, or dropped to tofu boxes — and nobody finds out
until a 대표이사 signs something that looks unprofessional.

Worse, **installing a Korean font is not sufficient.** Measured on this build:
after installing `fonts-noto-cjk`, LibreOffice still embedded WenQuanYi into the
PDF, because the document did not name a Korean family and fontconfig's generic
`:lang=ko` resolution still preferred the Chinese face.

Two conclusions, both load-bearing:

1. **Templates must name their family explicitly.** Relying on a generic
   fallback is relying on fontconfig's opinion, which differs between images.
2. **Substitution must be detected by us, not by fontconfig.** `FontResolver`
   resolves against the font store — which knows what is genuinely installed —
   and every fallback is named in a warning and recorded in the render metadata.
   A missing font never produces a silent substitution.

## The baseline we ship

**Pretendard** (SIL OFL 1.1) is the default family for the UI and for every
seeded template. It is free, redistributable and commercially usable, so it
ships in both the managed and on-prem builds without a per-client licence
conversation; its Korean and Latin are designed together, so mixed 한/영 lines
do not ransom-note the way Noto plus a separate Latin face does; and it has the
weight range a 결재 document needs.

Behind it: **Pretendard JP** where Japanese coverage is wanted, then **Noto Sans
/ Serif CJK** and Noto's broad coverage set for scripts Pretendard does not
cover. KoPub is optional for serif body text.

**함초롬바탕 / 함초롬돋움 are Hancom-licensed and are never bundled.** Clients
who own 한글 install them themselves under the section below, and that is the
supported answer rather than a limitation.

For PDF embedding, prefer **static instances over the variable build**.
Variable-font subsetting through fontkit/pdf-lib is the fussier path and
determinism matters more here than file size.

## Client-supplied fonts, at the client's own risk

Clients can install their own fonts: **any language, any script, any format** —
TTF, OTF, TTC, OTC, WOFF2, and whatever else fontconfig will take. No allowlist,
no curation, no restriction to Korean or Latin. A client running a CJK + Arabic
+ Devanagari document set can make that work without asking us.

**Licensing is entirely the client's responsibility, and the UI says so
unmistakably.** Uploading requires an explicit acknowledgement — a checkbox with
real words, not fine print — stating that the client warrants it holds the rights
to install and embed that font, that we neither verify nor indemnify, and that
embedding restrictions in the font's own metadata are theirs to honour.

The acknowledgement is stored **verbatim**, not as a boolean. A boolean records
that someone clicked; the text records what they agreed to, which is what an
argument years later turns on. The uploader and timestamp are recorded with it,
and the font's own `fsType` embedding permission is read out of its OS/2 table
and shown read-only beside the checkbox, so the person ticking it can see what
they are agreeing about.

## One font store, three consumers

An uploaded font must reach all three, from the same record:

| Consumer | How |
|---|---|
| The conversion worker | fontconfig path `/var/lib/coreintra/fonts`, `fc-cache` refreshed on install — no restart |
| The browser editor | served as a webfont from the same blob |
| mdv's PDF exporter | registered into `pdf.fonts` — **mdv will not pick up a CJK face implicitly**, because a full CJK face is 5–20 MB |

If any two of the three disagree about what a font looks like, the feature is
broken: WYSIWYG is the whole point.

## Substitution map

Client-editable, family → fallback chain, per script. Family-specific entries
take precedence over the script default. When a template references a family
that is not installed, the warning appears **at edit time and again at export
time**, naming the missing family *and* the substitute actually used, and the
substitution is recorded in the render metadata so a later re-render difference
can be explained.

## Backup

Fonts are part of `make backup` and part of the deployment's reproducibility
contract. A document re-rendered on a restored box must find the same fonts, or
the render metadata comparison will correctly report that it did not.

## Bulk installs on-prem

Mount a directory of fonts at `/var/lib/coreintra/fonts` on the host. They are
picked up by fontconfig on the next `fc-cache`, but they will show in the font
manager as `HOST_PROVIDED` with **no acknowledgement recorded** — the
responsibility question was never asked. For anything a client did not author
themselves, prefer the upload path so the acknowledgement exists.
