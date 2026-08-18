<!-- GENERATED from each adapter's declared FormatCapabilities.
     Do not edit by hand: a hand-maintained fidelity table drifts from the
     code silently, and the person it misleads is the user deciding whether
     an export is safe to send to a client.
     Regenerate with: ./mvnw -pl documents test -Dtest=FidelityMatrixTest -->

# Format fidelity

What survives, what degrades, and what is dropped, per format.

| Feature | DOCX (Word) | HWPX (한글) | HWP 5.0 (legacy binary) | mdv (Markdown Visual) |
|---|---|---|---|---|
| headings | yes | yes | yes | yes |
| bold / italic / underline / strikethrough | yes | yes | yes | yes |
| bulleted and numbered lists | yes | yes | yes | yes |
| tables | yes | yes | yes | yes |
| merged table cells | yes | ~ | ~ | no |
| images | yes | yes | ~ | ~ |
| page breaks | yes | yes | ~ | yes |
| typed fields (content controls) | yes | ~ | no | ~ |
| 결재란 | yes | ~ | no | ~ |
| headers and footers | yes | ~ | no | no |
| footnotes | yes | ~ | no | no |
| tracked changes | opaque | no | no | no |
| comments | opaque | no | no | no |
| charts | opaque | opaque | no | yes |
| complex-script shaping (Arabic, Indic) | yes | no | no | no |

Legend: **yes** survives · **~** degrades · **opaque** preserved but not editable · **no** dropped

## Read and write

| Format | Read | Write |
|---|---|---|
| DOCX (Word) | yes | yes |
| HWPX (한글) | yes | yes |
| HWP 5.0 (legacy binary) | yes | no |
| mdv (Markdown Visual) | yes | yes |

## Conversions, pair by pair

What changes converting one format to another. A conversion into the same format is not listed: an edit is surgical and leaves every untouched part byte-identical (see the DOCX note).

| From | To | Degrades | Dropped | Preserved but not editable |
|---|---|---|---|---|
| DOCX (Word) | HWPX (한글) | merged table cells, typed fields (content controls), 결재란, headers and footers, footnotes | tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| DOCX (Word) | HWP 5.0 (legacy binary) | — | — | — |
| DOCX (Word) | mdv (Markdown Visual) | images, typed fields (content controls), 결재란 | merged table cells, headers and footers, footnotes, tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| HWPX (한글) | DOCX (Word) | merged table cells, typed fields (content controls), 결재란, headers and footers, footnotes | tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| HWPX (한글) | HWP 5.0 (legacy binary) | — | — | — |
| HWPX (한글) | mdv (Markdown Visual) | images, typed fields (content controls), 결재란 | merged table cells, headers and footers, footnotes, tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| HWP 5.0 (legacy binary) | DOCX (Word) | merged table cells, images, page breaks | typed fields (content controls), 결재란, headers and footers, footnotes, tracked changes, comments, charts, complex-script shaping (Arabic, Indic) | — |
| HWP 5.0 (legacy binary) | HWPX (한글) | merged table cells, images, page breaks | typed fields (content controls), 결재란, headers and footers, footnotes, tracked changes, comments, charts, complex-script shaping (Arabic, Indic) | — |
| HWP 5.0 (legacy binary) | mdv (Markdown Visual) | images, page breaks | merged table cells, typed fields (content controls), 결재란, headers and footers, footnotes, tracked changes, comments, charts, complex-script shaping (Arabic, Indic) | — |
| mdv (Markdown Visual) | DOCX (Word) | images, typed fields (content controls), 결재란 | merged table cells, headers and footers, footnotes, tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| mdv (Markdown Visual) | HWPX (한글) | images, typed fields (content controls), 결재란 | merged table cells, headers and footers, footnotes, tracked changes, comments, complex-script shaping (Arabic, Indic) | charts |
| mdv (Markdown Visual) | HWP 5.0 (legacy binary) | — | — | — |

### Pair notes

- **mdv (Markdown Visual) → DOCX (Word)** — Charts materialise as embedded SVG, and the conversion worker is what materialises them: it renders each chart before the DOCX is written, because nothing in the JVM can draw one. They arrive as pictures — the figure is exact, and it is no longer a chart anyone can edit or re-bind to new data. A chart that reaches the DOCX writer unrendered (an export run without the worker) is written as a labelled placeholder naming the chart type, never dropped and never left blank.
- **mdv (Markdown Visual) → HWPX (한글)** — Charts materialise as embedded PNG rather than SVG, because 한글's picture handling for vector content is not reliable enough to promise. Rendered by the conversion worker, as for DOCX, and subject to the same placeholder rule when the worker is not in the path.
- **mdv (Markdown Visual) → HWP 5.0 (legacy binary)** — Legacy .hwp cannot be written at all; export to HWPX instead.

### Targets that are refused rather than approximated

- **DOCX (Word) → HWP 5.0 (legacy binary)** — HWP 5.0 (legacy binary) is read-only in this system: documents can be imported from it and never written back to it.

## Export targets produced by the conversion worker

These have no adapter: nothing here reads them and nothing here writes them. They are rendered outside the JVM, so what is true about them is declared in `RenderTarget` and reproduced here.

| Target | Produced by | Offered | What to know |
|---|---|---|---|
| PDF | headless LibreOffice (pinned version) | yes | Rendered server-side, never by the browser. What it looks like depends on the fonts installed in the conversion container: a family the container does not have is substituted, and the substitution is named in the render metadata. If a regenerated PDF ever differs from the archived one, the archived one is authoritative. |
| PDF (from an mdv document) | mdv's own exporter, in the conversion worker | **refused** | REFUSED for any document containing Korean or other non-WinAnsi text. The pinned mdv build embeds no fonts at all: its PDF exporter draws with the standard 14 faces and renders every Korean codepoint as `?`, reporting MDV5100. A PDF of 물음표 that says 결재 on the tab is worse than no PDF, so the worker refuses it and the LibreOffice path is used instead. |
| PDF/A-3b (archival) | mdv's own exporter, in the conversion worker | **refused** | REFUSED. The pinned mdv build accepts the profile and does nothing with it: no pdfaid XMP metadata, no OutputIntent, no embedded fonts — none of what PDF/A requires. Producing a file labelled archival that would fail validation is a promise this system would be making on the client's behalf to their auditor. |
| PDF/UA-1 (accessible) | mdv's own exporter, in the conversion worker | **refused** | REFUSED. The pinned build stamps the ISO 14289-1 conformance claim into a file whose fonts are not embedded, which that standard requires. It checks only that figures carry an /Alt. A false accessibility claim is worse than an absent one. |
| DOC (legacy Word 97) | headless LibreOffice (pinned version) | yes | Legacy and lossy, and labelled as such in the interface. Content controls do not exist in this format: the field values are written as ordinary text and the bindings are gone. Offered because counterparties still send and expect it. |

## Notes

### DOCX (Word)

The canonical storage format. Editing a stored DOCX does NOT round-trip through this adapter: OoxmlPackage edits the real package in place and leaves every untouched part byte-identical. Conversion through the pivot model is only for moving between formats, where a new document is expected.

### HWPX (한글)

Typed fields map onto 누름틀 (click-here fields), which is OWPML's own named editable region: the tag and the bound value survive a docx → HWPX → docx round trip AND survive the file being opened and saved by 한글, because the field is part of the format rather than a private annotation. The surrounding formatting may shift. A 결재란 written as a table survives as a table; its binding survives as a field, not as a Word structured document tag. Merged cells are written as unmerged cells of the same count. Content the model cannot express rides along as an attached package part so a docx round trip is lossless here, but any other program that opens and saves the file will discard it — after that the fields still carry every binding and the DOCX is rebuilt from the pivot model.

### HWP 5.0 (legacy binary)

Import only. Writing legacy .hwp would mean emitting a reverse-engineered binary; a file that 한글 opens with a repair prompt is worse than no file. Save as HWPX instead — it is the KS X 6101 standard and 한글 reads it. The original uploaded bytes are always retained and downloadable, so nothing is lost by importing.

### mdv (Markdown Visual)

Charts, financial plots and heatmaps are first-class here and are plain text in the source, so two versions of a board pack produce a readable diff — the one format in this system where that is true. THERE IS NO NATIVE DOCX OR HWP WRITER: those exports go mdv → InternalDoc → the DOCX/HWPX adapters. Rendering is not available in the JVM at all; it runs in Node in the conversion worker (ADR 0008), so on this side of the system every mdv block is carried verbatim as opaque content. Complex-script shaping is a Level 3 feature and this build substantiates Level 2, so Arabic and Indic are declared unsupported rather than misrendered.

