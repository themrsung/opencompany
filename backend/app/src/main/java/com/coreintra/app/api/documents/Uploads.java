package com.coreintra.app.api.documents;

import com.coreintra.compat.Texts;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reading an uploaded file, and refusing one that is too big before it is read.
 *
 * <h2>Why documents arrive as multipart and not as JSON</h2>
 *
 * <p>A document is bytes. Base64 in a JSON string costs a third again in size,
 * has to be held whole in memory twice — once as the encoded string, once
 * decoded — and turns a 20 MB HWP into a request body no proxy will log
 * usefully. {@code multipart/form-data} is what browsers already send and what
 * the size cap can be applied to before anything is read.
 *
 * <h2>The cap is checked twice on purpose</h2>
 *
 * <p>{@link MultipartFile#getSize()} is known from the part header, so an
 * oversized upload is refused without reading it. The container has its own
 * limit configured in {@code application.yml}; that one produces a multipart
 * parsing error that names neither the file nor the limit, which is no use to
 * the person who has just waited two minutes for an upload. This check exists
 * to answer them properly.
 */
public final class Uploads {

    /**
     * The biggest document this API accepts.
     *
     * <p>Chosen against what the store can actually do rather than what a disk
     * can hold: {@code BlobService.store} takes a {@code byte[]}, so the whole
     * file is in the heap for the length of the request. Raising this without
     * giving the store a streaming path is how a single upload takes the
     * application down.
     */
    public static final long DOCUMENT_MAX_BYTES = 32L * 1024L * 1024L;

    /** A full CJK face is 5-20 MB (§6.9), so the font cap has to clear that with room. */
    public static final long FONT_MAX_BYTES = 32L * 1024L * 1024L;

    private Uploads() {
    }

    /**
     * A sentence naming the file and the limit, or null when the upload fits.
     *
     * @param what the word for this kind of file, for the message
     */
    public static String tooLarge(MultipartFile file, long maxBytes, String what) {
        if (file == null || file.getSize() <= maxBytes) {
            return null;
        }
        return "The " + what + " \"" + name(file) + "\" is " + megabytes(file.getSize())
                + " MB. The limit is " + megabytes(maxBytes) + " MB.";
    }

    /** The bytes, or an {@link IllegalArgumentException} naming what was empty. */
    public static byte[] bytes(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("the " + what + " file is missing or empty");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            // Not the client's fault and not recoverable by retrying the same
            // request: the part was announced and then could not be read.
            throw new IllegalStateException("the uploaded " + what + " could not be read", e);
        }
    }

    /** The client's own filename, kept for the trail; never used as a path. */
    public static String name(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        return Texts.isBlank(original) ? "upload" : original;
    }

    private static String megabytes(long bytes) {
        return String.valueOf((bytes + 1024L * 1024L - 1L) / (1024L * 1024L));
    }
}
