package com.coreintra.documents.ooxml;

import com.coreintra.compat.Immutables;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * An OOXML package (a {@code .docx}) held as its parts, preserving everything
 * it was not asked to change.
 *
 * <h2>Why this exists rather than just using POI</h2>
 *
 * <p>The brief requires that opening and saving an untouched document changes
 * nothing. Any library that parses a document into an object model and
 * re-serialises it will fail that: namespace prefixes get renumbered, attribute
 * order shifts, whitespace moves, and anything the model does not understand is
 * quietly dropped. The differences are individually harmless and collectively
 * fatal — a 결재 document whose bytes change on every open cannot be hashed,
 * and a hash is what proves what was approved.
 *
 * <p>So parts are held as raw bytes and written back <b>verbatim</b> unless
 * something explicitly replaced them. Editing one field rewrites exactly one
 * part; the theme, the numbering definitions, the font table and the settings
 * come out identical to the byte. Anything this system does not model — a chart,
 * a VML shape, a custom XML part from the client's own tooling — survives
 * because it was never parsed in the first place.
 *
 * <p>Entry order is preserved too. {@code [Content_Types].xml} must come first
 * for some consumers, and reordering parts is a difference a strict reader can
 * see.
 *
 * <p>Not thread-safe. One package, one editing thread.
 */
public final class OoxmlPackage {

    /** The main document body part, where content controls live. */
    public static final String DOCUMENT_PART = "word/document.xml";

    /** Required first entry in a conformant package. */
    public static final String CONTENT_TYPES_PART = "[Content_Types].xml";

    private final List<String> partOrder;
    private final Map<String, byte[]> parts;
    private final Set<String> modifiedParts = new LinkedHashSet<String>();

    private OoxmlPackage(List<String> partOrder, Map<String, byte[]> parts) {
        this.partOrder = partOrder;
        this.parts = parts;
    }

    /**
     * @throws OoxmlException if the bytes are not a readable package, or lack a
     *         document part
     */
    public static OoxmlPackage read(byte[] docx) {
        if (docx == null || docx.length == 0) {
            throw new OoxmlException("the document is empty");
        }
        List<String> order = new ArrayList<String>();
        Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();

        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                order.add(entry.getName());
                parts.put(entry.getName(), readFully(zip));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new OoxmlException("this file is not a readable OOXML package", e);
        } finally {
            closeQuietly(zip);
        }

        if (!parts.containsKey(DOCUMENT_PART)) {
            throw new OoxmlException(
                    "no " + DOCUMENT_PART + " in the package. This may be a .doc (legacy binary), "
                            + "an .hwp, or a renamed file of another type entirely.");
        }
        return new OoxmlPackage(order, parts);
    }

    /** Part names, in their original order. */
    public List<String> partNames() {
        return Immutables.copyOf(partOrder);
    }

    public boolean hasPart(String name) {
        return parts.containsKey(name);
    }

    /**
     * A part's bytes.
     *
     * <p>Returns the live array rather than a copy: these are whole documents
     * and copying every read would be wasteful. Callers must not mutate it;
     * changes go through {@link #replacePart}.
     */
    public byte[] part(String name) {
        byte[] content = parts.get(name);
        if (content == null) {
            throw new OoxmlException("no part named " + name + " in this package");
        }
        return content;
    }

    public byte[] documentPart() {
        return part(DOCUMENT_PART);
    }

    /** Replaces a part and marks it modified, so the round-trip guarantee stays honest. */
    public void replacePart(String name, byte[] content) {
        if (!parts.containsKey(name)) {
            throw new OoxmlException(
                    "cannot replace " + name + ": no such part. Adding parts needs "
                            + "[Content_Types].xml and the relationship graph updated too.");
        }
        parts.put(name, content);
        modifiedParts.add(name);
    }

    /** Which parts were replaced since reading. Empty means a pure round-trip. */
    public Set<String> modifiedParts() {
        return Immutables.setCopyOf(modifiedParts);
    }

    public boolean isUnmodified() {
        return modifiedParts.isEmpty();
    }

    /**
     * Writes the package back out.
     *
     * <p>Every part not explicitly replaced is written with its original bytes,
     * in its original position.
     *
     * <p>The resulting <em>archive</em> bytes may still differ from the input —
     * zip timestamps and the deflate level are not part of the document — so the
     * round-trip guarantee is stated over parts, not over the container. Content
     * hashing for the approval trail is likewise over parts, for the same reason.
     */
    public byte[] write() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(estimatedSize());
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (String name : partOrder) {
                byte[] content = parts.get(name);
                if (content == null) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(name);
                // A fixed timestamp keeps output deterministic: the same package
                // written twice produces the same bytes, which matters for the
                // reproducibility contract around archived approvals.
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException e) {
            throw new OoxmlException("failed to write the OOXML package", e);
        } finally {
            closeQuietly(zip);
        }
        return out.toByteArray();
    }

    private int estimatedSize() {
        int total = 0;
        for (byte[] content : parts.values()) {
            total += content.length;
        }
        return Math.max(1024, total / 2);
    }

    private static byte[] readFully(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a byte-array-backed stream cannot meaningfully fail, and
            // masking the real exception from the try block would be worse.
        }
    }
}
