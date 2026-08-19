package com.coreintra.documents.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.entity.FontSubstitutionRuleEntity;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.font.FontSubstitutionMap;
import com.coreintra.documents.repository.DocumentRenderRepository;
import com.coreintra.documents.repository.FontRepository;
import com.coreintra.documents.repository.FontSubstitutionRuleRepository;

/**
 * The font store: install, disable, remove, and resolve.
 *
 * <h2>Licensing is the client's, and the record proves they said so</h2>
 *
 * <p>Clients install their own fonts - any language, any script, any format - and we
 * neither verify nor indemnify (brief 6.9). What we do is record who accepted that, when,
 * and in exactly what words. {@link #installClientFont} cannot be called without the
 * acknowledgement text, which is the point: an unrecorded acceptance is not an acceptance,
 * and a boolean flag would be a claim rather than evidence.
 *
 * <h2>Substitution is never silent</h2>
 *
 * <p>{@link #resolverFor} hands back a resolver built from what is genuinely installed,
 * not from fontconfig - which always answers, and answered {@code DejaVu Sans} when this
 * build asked it for Pretendard. Every fallback the resolver makes is named, and the
 * caller records it in the render metadata.
 */
@Service
public class FontStoreService {

    private final FontRepository fonts;

    private final FontSubstitutionRuleRepository substitutions;

    private final DocumentRenderRepository renders;

    private final BlobService blobs;

    public FontStoreService(FontRepository fonts, FontSubstitutionRuleRepository substitutions,
            DocumentRenderRepository renders, BlobService blobs) {
        this.fonts = fonts;
        this.substitutions = substitutions;
        this.renders = renders;
        this.blobs = blobs;
    }

    /**
     * Installs a font the client uploaded, under the client's own licence.
     *
     * @param licenceAcknowledgementText the exact words the uploader ticked, stored verbatim
     * @param embedding read out of the font's own OS/2 table and shown read-only beside the
     *        acknowledgement, so the person ticking the box can see what they are agreeing
     *        about rather than being asked to trust us
     * @throws IllegalArgumentException if the acknowledgement is blank or nobody is named -
     *         the record is the only evidence anyone took responsibility, so it cannot be
     *         optional at this layer any more than it is at the database's
     */
    @Transactional
    public FontEntity installClientFont(String companyId, String family, String style,
            String fileFormat, byte[] content, String originalFilename, Set<String> scriptCoverage,
            EmbeddingDeclaration embedding, String uploadedByAccountId,
            String licenceAcknowledgementText) {
        if (Texts.isBlank(companyId)) {
            throw new IllegalArgumentException(
                    "a client-uploaded font belongs to the client that accepted its licence");
        }
        if (Texts.isBlank(licenceAcknowledgementText)) {
            throw new IllegalArgumentException(
                    "a font upload needs the licence acknowledgement the uploader accepted, "
                    + "recorded in the words they saw. Without it there is no evidence anyone "
                    + "took responsibility, which is the entire point of asking.");
        }
        FontEntity font = newFont(companyId, family, style, fileFormat, content, originalFilename,
                scriptCoverage, FontRecord.Source.CLIENT_UPLOADED);
        EmbeddingDeclaration declared = embedding == null
                ? EmbeddingDeclaration.unreadable()
                : embedding;
        font.declaresEmbedding(declared.permission(), declared.fsTypeRaw());
        font.acknowledgedBy(uploadedByAccountId, OffsetDateTime.now(), licenceAcknowledgementText);
        return fonts.save(font);
    }

    /**
     * Registers a font that shipped with the product.
     *
     * <p>No acknowledgement, because we hold the redistribution rights ourselves - Pretendard
     * is SIL OFL 1.1 and can be bundled in both the managed and on-prem builds with no
     * per-client licence conversation. A bundled font has no company: it is available to all.
     */
    @Transactional
    public FontEntity registerBundledFont(String family, String style, String fileFormat,
            byte[] content, String originalFilename, Set<String> scriptCoverage) {
        return fonts.save(newFont(null, family, style, fileFormat, content, originalFilename,
                scriptCoverage, FontRecord.Source.BUNDLED));
    }

    /** Stops offering a font without removing it. Reversible, and it breaks nothing archived. */
    @Transactional
    public FontEntity disable(String fontId, OffsetDateTime at) {
        FontEntity font = require(fontId);
        font.disable(at);
        return fonts.save(font);
    }

    @Transactional
    public FontEntity enable(String fontId) {
        FontEntity font = require(fontId);
        font.enable();
        return fonts.save(font);
    }

    /** What removing this font would cost. Shown before the removal, never after. */
    @Transactional(readOnly = true)
    public FontRemovalImpact impactOfRemoving(String fontId) {
        FontEntity font = require(fontId);
        return new FontRemovalImpact(fontId, font.family(),
                renders.countByFontSetContaining(font.family()));
    }

    /**
     * Removes a font, having shown the caller what it costs.
     *
     * @param acknowledgedAffectedCount the count the caller was shown by
     *        {@link #impactOfRemoving}. Passed back so the warning cannot be skipped, and
     *        re-checked so it cannot be stale: a render made between the warning and the
     *        removal is exactly the one whose reproducibility would be lost quietly.
     * @throws IllegalStateException if the count has changed since the caller looked
     */
    @Transactional
    public FontEntity remove(String fontId, long acknowledgedAffectedCount, OffsetDateTime at) {
        FontRemovalImpact impact = impactOfRemoving(fontId);
        if (impact.affectedRenderCount() != acknowledgedAffectedCount) {
            throw new IllegalStateException(
                    "the number of renders using \"" + impact.family() + "\" changed from "
                    + acknowledgedAffectedCount + " to " + impact.affectedRenderCount()
                    + " while you were deciding. " + impact.warningEn());
        }
        FontEntity font = require(fontId);
        font.retire(at);
        // The bytes stay: an archived render's font set names this hash, and reaping it
        // would turn "the fonts differed" into "the font is gone", which explains nothing.
        return fonts.save(font);
    }

    /** Everything installed for one company: its own uploads plus the bundled set. */
    @Transactional(readOnly = true)
    public List<InstalledFont> installedFor(String companyId) {
        List<InstalledFont> installed = new ArrayList<InstalledFont>();
        for (FontEntity font : fonts.findInstalledFor(companyId)) {
            installed.add(new InstalledFont(font));
        }
        return Immutables.copyOf(installed);
    }

    /**
     * A resolver over what is genuinely installed, with the client's own fallback chains.
     *
     * <p>Not fontconfig. fontconfig always answers: asked for a family it does not have it
     * returns its best guess and says nothing, and a 지출결의서 set in a Chinese face is not
     * obviously broken - it is subtly wrong, and nobody finds out until a 대표이사 signs it.
     */
    @Transactional(readOnly = true)
    public FontResolver resolverFor(String companyId) {
        List<FontRecord> records = new ArrayList<FontRecord>();
        for (FontEntity font : fonts.findInstalledFor(companyId)) {
            records.add(font.toRecord());
        }
        return new FontResolver(records, substitutionMapFor(companyId));
    }

    /**
     * The client's substitution map, over the shipped defaults.
     *
     * <p>A client rule for a family replaces the shipped chain for that family rather than
     * appending to it: a client who maps 함초롬바탕 to their own licensed face means that
     * face, not that face followed by our guesses.
     */
    @Transactional(readOnly = true)
    public FontSubstitutionMap substitutionMapFor(String companyId) {
        FontSubstitutionMap shipped = FontSubstitutionMap.shippedDefault();
        FontSubstitutionMap.Builder builder = FontSubstitutionMap.builder();
        for (Map.Entry<String, List<String>> entry : shipped.familyChains().entrySet()) {
            builder.mapFamily(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        for (Map.Entry<String, List<String>> entry : shipped.scriptChains().entrySet()) {
            builder.mapScript(entry.getKey(), entry.getValue().toArray(new String[0]));
        }

        Map<String, List<String>> families = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> scripts = new LinkedHashMap<String, List<String>>();
        for (FontSubstitutionRuleEntity rule
                : substitutions.findByCompanyIdOrderByScopeAscScopeKeyAscPositionAsc(companyId)) {
            Map<String, List<String>> target =
                    rule.scope() == FontSubstitutionRuleEntity.Scope.FAMILY ? families : scripts;
            List<String> chain = target.get(rule.scopeKey());
            if (chain == null) {
                chain = new ArrayList<String>();
                target.put(rule.scopeKey(), chain);
            }
            chain.add(rule.fallbackFamily());
        }
        for (Map.Entry<String, List<String>> entry : families.entrySet()) {
            builder.mapFamily(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        for (Map.Entry<String, List<String>> entry : scripts.entrySet()) {
            builder.mapScript(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return builder.build();
    }

    /** Adds one link to a client-editable chain. Order is the client's preference, kept. */
    @Transactional
    public FontSubstitutionRuleEntity addSubstitution(String companyId,
            FontSubstitutionRuleEntity.Scope scope, String scopeKey, int position,
            String fallbackFamily) {
        return substitutions.save(new FontSubstitutionRuleEntity(UUID.randomUUID().toString(),
                companyId, scope, scopeKey, position, fallbackFamily));
    }

    private FontEntity newFont(String companyId, String family, String style, String fileFormat,
            byte[] content, String originalFilename, Set<String> scriptCoverage,
            FontRecord.Source source) {
        if (Texts.isBlank(family)) {
            throw new IllegalArgumentException(
                    "a font needs a family name; it is the key documents reference it by");
        }
        String resolvedStyle = Texts.isBlank(style) ? "Regular" : style;
        Optional<FontEntity> clash =
                fonts.findByCompanyIdAndFamilyAndStyle(companyId, family, resolvedStyle);
        if (clash.isPresent() && clash.get().retiredAt() == null) {
            throw new IllegalArgumentException(
                    "\"" + family + " " + resolvedStyle + "\" is already installed. Two rows for "
                    + "one family would leave the resolver picking one of them, and the browser "
                    + "and the PDF could pick differently.");
        }
        BlobRef ref = blobs.store(content, "font/" + fileFormat.toLowerCase(Locale.ROOT),
                originalFilename);
        return new FontEntity(UUID.randomUUID().toString(), companyId, family, resolvedStyle,
                fileFormat, ref.sha256(), source, scriptCoverage);
    }

    private FontEntity require(String fontId) {
        return fonts.findById(fontId).orElseThrow(
                () -> new IllegalArgumentException("no such font: " + fontId));
    }
}
