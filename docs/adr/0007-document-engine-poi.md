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

## Consequences

- POI XWPF's content-control (`w:sdt`) support is thinner than docx4j's, so the
  content-control read/write path is implemented against the underlying
  `CTSdtBlock`/`CTSdtRun` XmlBeans types rather than a high-level API. That code
  is concentrated in one adapter and covered by the round-trip equality test.
- Anything POI cannot model is preserved as opaque XML rather than dropped. The
  normalized-XML equality test on an untouched real-world docx is what proves
  this, and it is the test that must never be weakened.
- POI is not thread-safe per document. Document operations take a per-document
  lock; the conversion worker is where parallelism lives.
