package com.coreintra.app.api.fonts;

import com.coreintra.app.api.documents.BusinessInstants;
import com.coreintra.app.api.documents.DocumentProblems;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.service.BlobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The font bytes, addressed by content hash, for the browser editor.
 *
 * <h2>Why this route and not {@code /api/v1}</h2>
 *
 * <p>The {@code @font-face} rule is generated in the documents module —
 * {@code InstalledFont#webfontFaceCss} — and points at {@code /api/fonts/<hash>}.
 * The URL is therefore part of the store's contract with the browser, not of the
 * versioned resource API, and it is served here to match rather than being
 * changed to something tidier that would leave the generated CSS pointing at a
 * 404.
 *
 * <p>Content-addressed on purpose: the URL changes when the bytes do, so a
 * replaced font cannot be served from a stale browser cache while the PDF uses
 * the new one — which is exactly the two-of-three disagreement §6.9 is about.
 * That also makes the response immutable and safe to cache for a year.
 */
@RestController
@RequestMapping("/api/fonts")
@Tag(name = "Fonts — webfont",
        description = "Font bytes by content hash, for the browser editor. One record feeds the "
                + "editor, the conversion worker and mdv, so all three render alike.")
public class WebFontController {

    private final FontAccess access;
    private final BlobService blobs;
    private final CurrentPrincipal current;

    public WebFontController(FontAccess access, BlobService blobs, CurrentPrincipal current) {
        this.access = access;
        this.blobs = blobs;
        this.current = current;
    }

    @GetMapping("/{sha256}")
    @Operation(summary = "Serve an installed font to the editor",
            description = "Requires documents.font:read on a company that has this font "
                    + "installed; a bundled face is decided installation-wide because it ships "
                    + "with the product and belongs to no company. Immutable: the hash is the "
                    + "identity of the bytes.")
    public ResponseEntity<Object> font(@PathVariable String sha256,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<FontEntity> font = access.byContentHash(caller, sha256,
                BusinessInstants.date(businessDate));
        if (!font.isPresent()) {
            // Not found and not permitted are answered the same way here, and
            // that is deliberate: the hash is guessable in principle, and a
            // distinct 403 would confirm which fonts an installation has.
            return DocumentProblems.notFound("installed font", sha256);
        }
        return ResponseEntity.ok()
                .header("Content-Type", "font/" + font.get().fileFormat().toLowerCase(
                        java.util.Locale.ROOT))
                .cacheControl(CacheControl.maxAge(365L, java.util.concurrent.TimeUnit.DAYS)
                        .cachePublic())
                .body((Object) blobs.contentOf(sha256));
    }
}
