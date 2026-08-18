<!-- GENERATED from each adapter's declared FormatCapabilities.
     Do not edit by hand: a hand-maintained fidelity table drifts from the
     code silently, and the person it misleads is the user deciding whether
     an export is safe to send to a client.
     Regenerate with: ./mvnw -pl documents test -Dtest=FidelityMatrixTest -->

# Format fidelity

What survives, what degrades, and what is dropped, per format.

| Feature | DOCX (Word) | HWPX (한글) | HWP 5.0 (legacy binary) |
|---|---|---|---|
| headings | yes | yes | yes |
| bold / italic / underline / strikethrough | yes | yes | yes |
| bulleted and numbered lists | yes | yes | yes |
| tables | yes | yes | yes |
| merged table cells | yes | ~ | ~ |
| images | yes | yes | ~ |
| page breaks | yes | yes | ~ |
| typed fields (content controls) | yes | ~ | no |
| 결재란 | yes | ~ | no |
| headers and footers | yes | ~ | no |
| footnotes | yes | ~ | no |
| tracked changes | opaque | no | no |
| comments | opaque | no | no |
| charts | opaque | no | no |
| complex-script shaping (Arabic, Indic) | yes | no | no |

Legend: **yes** survives · **~** degrades · **opaque** preserved but not editable · **no** dropped

## Read and write

| Format | Read | Write |
|---|---|---|
| DOCX (Word) | yes | yes |
| HWPX (한글) | yes | yes |
| HWP 5.0 (legacy binary) | yes | no |

## Notes

### DOCX (Word)

The canonical storage format. Editing a stored DOCX does NOT round-trip through this adapter: OoxmlPackage edits the real package in place and leaves every untouched part byte-identical. Conversion through the pivot model is only for moving between formats, where a new document is expected.

### HWPX (한글)

Typed fields map onto HWPX's own field mechanism. The tag and the bound value survive a docx → HWPX → docx round trip; the surrounding formatting may shift. Charts become embedded images where possible and are otherwise dropped — they are never silently flattened into a picture that looks editable.

### HWP 5.0 (legacy binary)

Import only. Writing legacy .hwp would mean emitting a reverse-engineered binary; a file that 한글 opens with a repair prompt is worse than no file. Save as HWPX instead — it is the KS X 6101 standard and 한글 reads it. The original uploaded bytes are always retained and downloadable, so nothing is lost by importing.

