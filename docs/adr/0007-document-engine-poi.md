# 7. DOCX engine is Apache POI XWPF, forced by the Java 8 baseline

- Status: accepted
- Date: 2026-08-18

## Problem

The brief (SS6.1) leaves the DOCX engine open between docx4j and Apache POI
XWPF, with the instruction to check which line still supports Java 8 before
committing.

## Decision

**Apache POI XWPF 5.5.1.** Verified by reading the class file major version out
of the published jars rather than trusting release notes:

| Artifact | Class major | Runs on Java 8 |
|---|---|---|
| `org.apache.poi:poi-ooxml:5.5.1` | 52 | yes |
| `org.docx4j:docx4j-core:17.0.2` | 55 | no |
| `org.docx4j:docx4j-core:11.5.5` | 55 | no |

docx4j has no Java 8 line at all — even the old 11.x branch is compiled for
Java 11. Under ADR 1 that ends the comparison; nothing else about the two
libraries matters if one cannot load.

The choice sits behind a `DocumentEngine` interface. No POI type appears in any
domain signature, so if the Java baseline moves and docx4j becomes viable, the
swap is one implementation class.

## The second decision: do not re-serialise

Choosing POI settled *which library*, not *how documents are handled*. The brief
requires that opening and saving an untouched document change nothing, and no
object model can offer that — re-serialisation renumbers namespace prefixes,
reorders attributes, moves whitespace, and silently drops anything the model
does not understand.

Measured, not assumed. Opening the test fixture through POI and saving it
unchanged alters or drops **11 of its 12 parts**:

```
_rels/.rels, docProps/core.xml, docProps/app.xml, docProps/custom.xml,
word/_rels/document.xml.rels, word/document.xml, word/styles.xml,
word/numbering.xml, word/settings.xml, word/theme/theme1.xml,
[Content_Types].xml
```

So documents are held as an `OoxmlPackage`: parts as raw bytes, written back
verbatim unless explicitly replaced. Editing one field rewrites exactly one part.
Anything this system does not model — a chart, a VML shape, a client's custom XML
part — survives because it is never parsed.

POI remains the engine for *creating* documents from scratch, where there is no
original to preserve. `WhyNotReserialiseTest` keeps this evidence live: if POI
ever becomes byte-stable that test fails, which is the signal to revisit this.

## Consequences of that second decision

## Consequences of that second decision

- Field binding works at the XML level: content controls are read and written by
  DOM manipulation of `word/document.xml`, not through a high-level API. That is
  a real cost, paid in one class, and it is what buys the round-trip guarantee.
- The guarantee is stated over **parts**, not over the archive. Zip timestamps
  and the deflate level are not part of the document, so `write()` pins the
  timestamp to keep output deterministic and the tests compare part bytes.
- Untrusted documents are parsed with DTDs and external entities disabled. A
  document parser reachable by anyone who can upload a file is an XXE primitive
  otherwise.
- POI is not thread-safe per document. Document operations take a per-document
  lock; the conversion worker is where parallelism lives.
