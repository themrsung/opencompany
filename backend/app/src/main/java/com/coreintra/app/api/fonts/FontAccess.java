package com.coreintra.app.api.fonts;

import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.repository.FontRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a font id, or a content hash, into a row the evaluator can decide about.
 *
 * <p>The same translation {@code DocumentAccess} performs for documents, and it
 * exists for the same reason: {@code FontStoreService} takes a bare
 * {@code companyId} and no caller, so somebody has to ask the evaluator, and §3
 * says the question is asked about the domain object rather than about the
 * route. It should disappear into the documents module when that service grows a
 * caller.
 *
 * <h2>A bundled font belongs to the installation</h2>
 *
 * <p>A client-uploaded font is company-scoped. A bundled one has no company —
 * Pretendard ships with the product — so it is decided against an
 * installation-wide target. Treating a bundled font as belonging to whichever
 * company happened to ask would make Pretendard invisible to a caller whose
 * grant is scoped to their own company, which is every ordinary user.
 */
@Service
public class FontAccess {

    private final FontRepository fonts;
    private final PermissionEvaluator evaluator;

    public FontAccess(FontRepository fonts, PermissionEvaluator evaluator) {
        this.fonts = fonts;
        this.evaluator = evaluator;
    }

    /** Checks a company-wide capability: listing, installing, editing substitutions. */
    @Transactional(readOnly = true)
    public void company(PermissionPrincipal caller, String companyId, PermissionKey key,
            LocalDate on, String description) {
        evaluator.check(caller, key, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(on)
                .description(description)
                .build()).orThrow();
    }

    /**
     * The font, once the caller may do {@code key} to it.
     *
     * @return empty when no font has that id
     */
    @Transactional(readOnly = true)
    public Optional<FontEntity> font(PermissionPrincipal caller, String fontId, PermissionKey key,
            LocalDate on, String description) {
        Optional<FontEntity> found = fonts.findById(fontId);
        if (!found.isPresent()) {
            return found;
        }
        evaluator.check(caller, key, targetFor(found.get(), on, description)).orThrow();
        return found;
    }

    /**
     * A font by content hash, for the webfont route, if the caller may read it.
     *
     * <p>Several rows can share bytes — the same face installed by two companies
     * is one blob — so this returns the first the caller is allowed to read
     * rather than refusing on the first they are not.
     */
    @Transactional(readOnly = true)
    public Optional<FontEntity> byContentHash(PermissionPrincipal caller, String sha256,
            LocalDate on) {
        List<FontEntity> candidates = fonts.findByBlobSha256AndRetiredAtIsNull(sha256);
        for (FontEntity font : candidates) {
            if (evaluator.check(caller, com.coreintra.app.api.documents.DocumentPermissions.FONT_READ,
                    targetFor(font, on, "serving font " + font.family())).isAllowed()) {
                return Optional.of(font);
            }
        }
        return Optional.empty();
    }

    private static PermissionTarget targetFor(FontEntity font, LocalDate on, String description) {
        if (font.companyId() == null) {
            return PermissionTarget.installationWide(on);
        }
        return PermissionTarget.builder()
                .companyId(font.companyId())
                .asOfBusinessDate(on)
                .description(description)
                .build();
    }
}
