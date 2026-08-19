package com.coreintra.documents.signature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.stereotype.Service;

/**
 * Puts a seal where the renderer can find it, and nowhere a browser can.
 *
 * <p>The bytes go to a file in the render workspace - a directory the conversion job owns
 * and deletes when it finishes - rather than being returned to the caller. That is the
 * whole design: the only exit from {@link SignatureImpression} leads to a path the caller
 * already had to create, and there is no return value anyone could write to an HTTP
 * response.
 *
 * <p>The workspace must never be a directory the web layer serves. That is a deployment
 * fact rather than something this class can check, and it is stated in
 * {@code docs/deploy/} beside the blob path.
 */
@Service
public class SignatureCompositor {

    /**
     * Writes the seal into the workspace.
     *
     * @param workspaceDirectory a directory the caller created for one render and will
     *        delete after it
     * @return the file name written, relative to the workspace. A name, not content: what
     *         the renderer needs in its layout instructions and the most a caller can leak.
     * @throws IllegalArgumentException if the workspace does not exist - creating it here
     *         would mean a typo silently scatters seals across the filesystem
     */
    public String materialiseInto(Path workspaceDirectory, SignatureImpression impression) {
        if (workspaceDirectory == null || !Files.isDirectory(workspaceDirectory)) {
            throw new IllegalArgumentException(
                    "the render workspace must exist before a seal is written into it: "
                    + workspaceDirectory);
        }
        String fileName = "seal-" + impression.signatureImageId() + extensionFor(impression);
        Path target = workspaceDirectory.resolve(fileName);
        try {
            Files.write(target, impression.image(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not write the seal into the render workspace " + workspaceDirectory
                    + ". The render must fail rather than produce a 결재 document with a blank "
                    + "결재란, which would look signed.", e);
        }
        return fileName;
    }

    /**
     * Removes a materialised seal.
     *
     * <p>The job deletes its whole workspace, so this is belt and braces - but a seal left
     * on disk after a crash is the one file in the workspace that matters, and leaving it
     * to a cleanup nobody watches is how it stays there.
     */
    public void discard(Path workspaceDirectory, String fileName) {
        try {
            Files.deleteIfExists(workspaceDirectory.resolve(fileName));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not remove the materialised seal " + fileName + " from "
                    + workspaceDirectory, e);
        }
    }

    private static String extensionFor(SignatureImpression impression) {
        String type = impression.contentType();
        if (type == null) {
            return ".bin";
        }
        if (type.endsWith("png")) {
            return ".png";
        }
        if (type.endsWith("jpeg") || type.endsWith("jpg")) {
            return ".jpg";
        }
        if (type.endsWith("svg+xml")) {
            return ".svg";
        }
        return ".bin";
    }
}
