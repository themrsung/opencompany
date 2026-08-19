package com.coreintra.documents.internal.adapter.hwpx;

import com.coreintra.documents.ooxml.OoxmlException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

/**
 * The container half of HWPX: getting hwpxlib's object model in and out of a
 * {@code byte[]}, and making the archive it produces reproducible.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Three gaps in hwpxlib 1.0.9, each found by running it rather than by
 * reading about it, and each of which would otherwise leak into every caller:
 *
 * <ol>
 *   <li>{@link HWPXReader} has no {@code byte[]} or {@link java.io.InputStream}
 *       entry point — only {@code fromFilepath} and {@code fromFile}. Every
 *       document in this system arrives as bytes from the blob store, so the
 *       temporary file is unavoidable; it is confined here and always deleted.</li>
 *   <li>{@link HWPXWriter} stamps every zip entry with the current time, so the
 *       same document written twice produces different bytes. That silently
 *       destroys the reproducibility contract the approval trail depends on —
 *       measured: two writes of an identical model two seconds apart differ in
 *       SHA-256. {@link #normalise} fixes the timestamps at zero, exactly as
 *       {@code OoxmlPackage.write()} does for DOCX and for the same reason.</li>
 *   <li>hwpxlib writes {@code mimetype} <em>deflated</em>. HWPX is an OCF-style
 *       container, where the first entry must be an uncompressed {@code mimetype}
 *       so a reader can identify the format from the first bytes of the file
 *       without inflating anything. {@link #normalise} restores that.</li>
 * </ol>
 *
 * <p>Both corrections are verified to survive a re-read: a normalised package
 * parses back through {@link HWPXReader} unchanged.
 */
public final class HwpxPackage {

    /** The OCF entry that must come first, stored rather than deflated. */
    private static final String MIMETYPE_ENTRY = "mimetype";

    private HwpxPackage() {
    }

    /**
     * Parses HWPX bytes into hwpxlib's object model.
     *
     * @throws OoxmlException if the bytes are not a readable HWPX package, with
     *         a message naming the likely actual type rather than the parser's
     */
    public static HWPXFile read(byte[] content) {
        if (content == null || content.length == 0) {
            throw new OoxmlException("the document is empty");
        }
        File temporary = null;
        try {
            temporary = File.createTempFile("coreintra-hwpx-", ".hwpx");
            OutputStream out = new FileOutputStream(temporary);
            try {
                out.write(content);
            } finally {
                out.close();
            }
            return HWPXReader.fromFile(temporary);
        } catch (IOException e) {
            throw new OoxmlException("failed to read the HWPX package", e);
        } catch (RuntimeException e) {
            throw new OoxmlException(
                    "this file is not a readable HWPX package. It may be a legacy .hwp (binary), "
                            + "a DOCX renamed to .hwpx, or corrupt.", e);
        } catch (Exception e) {
            // hwpxlib declares a bare `throws Exception` on its reader.
            throw new OoxmlException(
                    "this file is not a readable HWPX package. It may be a legacy .hwp (binary), "
                            + "a DOCX renamed to .hwpx, or corrupt.", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /** Serialises the object model, then makes the archive reproducible. */
    public static byte[] write(HWPXFile file) {
        return write(file, null);
    }

    /**
     * Serialises the object model together with parts hwpxlib will not write.
     *
     * <p>Measured: {@link HWPXWriter} emits a manifest item's attached bytes
     * <b>only when its media type begins with {@code image/}</b>. It writes the
     * {@code <opf:item>} declaration for anything, but silently omits the file
     * itself for everything else — so a package part carrying, say, a preserved
     * DOCX body is declared and absent, which is worse than either extreme.
     *
     * <p>Rather than disguising such a part as an image to get it written, the
     * bytes are injected here, in the one place that already rewrites the
     * archive. The manifest still describes them, so the package remains
     * self-describing.
     *
     * @param extraParts entry name to content, or null
     */
    public static byte[] write(HWPXFile file, Map<String, byte[]> extraParts) {
        try {
            return normalise(HWPXWriter.toBytes(file), extraParts);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new OoxmlException("failed to write the HWPX package", e);
        }
    }

    /**
     * Rewrites the archive with fixed timestamps and a stored, leading
     * {@code mimetype}.
     *
     * <p>Entry order is otherwise preserved: the reader does not care, but a
     * human diffing two packages does, and so does anyone comparing this
     * output against a file hwpxlib produced.
     */
    static byte[] normalise(byte[] archive) {
        return normalise(archive, null);
    }

    static byte[] normalise(byte[] archive, Map<String, byte[]> extraParts) {
        Map<String, byte[]> entries = entriesOf(archive);
        if (extraParts != null) {
            entries.putAll(extraParts);
        }
        byte[] mimetype = entries.remove(MIMETYPE_ENTRY);

        ByteArrayOutputStream out = new ByteArrayOutputStream(archive.length + 512);
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            if (mimetype != null) {
                ZipEntry entry = new ZipEntry(MIMETYPE_ENTRY);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(mimetype.length);
                entry.setCompressedSize(mimetype.length);
                CRC32 crc = new CRC32();
                crc.update(mimetype, 0, mimetype.length);
                entry.setCrc(crc.getValue());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(mimetype);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> part : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(part.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException e) {
            throw new OoxmlException("failed to normalise the HWPX package", e);
        } finally {
            closeQuietly(zip);
        }
        return out.toByteArray();
    }

    /** Every entry in the archive, in order. Used by the tests and by normalise. */
    public static Map<String, byte[]> entriesOf(byte[] archive) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), readFully(zip));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new OoxmlException("this file is not a readable HWPX package", e);
        } finally {
            closeQuietly(zip);
        }
        return entries;
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

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
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
