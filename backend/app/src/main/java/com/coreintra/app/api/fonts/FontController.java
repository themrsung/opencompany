package com.coreintra.app.api.fonts;

import com.coreintra.app.api.documents.BusinessInstants;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.api.documents.DocumentProblems;
import com.coreintra.app.api.documents.FontWarningView;
import com.coreintra.app.api.documents.Uploads;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.entity.FontSubstitutionRuleEntity;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.font.FontSubstitutionMap;
import com.coreintra.documents.service.EmbeddingDeclaration;
import com.coreintra.documents.service.FontRemovalImpact;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.InstalledFont;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The font manager (§6.9).
 *
 * <h2>Uploading requires an acknowledgement, and cannot be defaulted into</h2>
 *
 * <p>Licensing is entirely the client's responsibility and the interface must
 * say so unmistakably. That obligation reaches the API: an upload without an
 * explicit acknowledgement is refused, the acknowledgement cannot arrive by
 * omission or as a field that defaults to true, and the words agreed to are the
 * server's own — echoed back by the client to prove they were displayed — so
 * that the record is evidence rather than a self-report.
 *
 * <p>What is stored is who acknowledged, when, and the exact text.
 *
 * <h2>Removal warns before, not after</h2>
 *
 * <p>{@code removal-impact} returns the number of archived renders that used the
 * font, so the interface can warn before anything happens; the removal itself
 * then has to state that number back, and is refused if it changed in between.
 * A font removed under a stale count is a set of approved documents that quietly
 * re-render in a different face.
 */
@RestController
@RequestMapping("/api/v1/fonts")
@Tag(name = "Fonts",
        description = "Client-supplied fonts: any language, any script, any format, at the "
                + "client's own risk and with an explicit licence acknowledgement.")
public class FontController {

    private final FontStoreService fonts;
    private final FontAccess access;
    private final CurrentPrincipal current;

    public FontController(FontStoreService fonts, FontAccess access, CurrentPrincipal current) {
        this.fonts = fonts;
        this.access = access;
        this.current = current;
    }

    /** The wording a client must display and echo back. */
    public static class AcknowledgementWording {
        public String getVersion() {
            return LicenceAcknowledgement.VERSION;
        }

        public String getTextKo() {
            return LicenceAcknowledgement.TEXT_KO;
        }

        public String getTextEn() {
            return LicenceAcknowledgement.TEXT_EN;
        }
    }

    /** What removing a font would cost. */
    public static class RemovalImpactView {
        private final String fontId;
        private final String family;
        private final long affectedRenderCount;
        private final boolean safe;
        private final String warningKo;
        private final String warningEn;

        RemovalImpactView(FontRemovalImpact impact) {
            this.fontId = impact.fontId();
            this.family = impact.family();
            this.affectedRenderCount = impact.affectedRenderCount();
            this.safe = impact.isSafe();
            this.warningKo = impact.warningKo();
            this.warningEn = impact.warningEn();
        }

        public String getFontId() {
            return fontId;
        }

        public String getFamily() {
            return family;
        }

        /** Archived renders that used this font. Echo it back to remove the font. */
        public long getAffectedRenderCount() {
            return affectedRenderCount;
        }

        public boolean isSafe() {
            return safe;
        }

        public String getWarningKo() {
            return warningKo;
        }

        public String getWarningEn() {
            return warningEn;
        }
    }

    /** The client-editable family → fallback chain. */
    public static class SubstitutionMapView {
        private final Map<String, List<String>> familyChains;
        private final Map<String, List<String>> scriptChains;

        SubstitutionMapView(FontSubstitutionMap map) {
            this.familyChains = Immutables.mapCopyOf(map.familyChains());
            this.scriptChains = Immutables.mapCopyOf(map.scriptChains());
        }

        /** Requested family → the chain to try instead. A client rule replaces the shipped one. */
        public Map<String, List<String>> getFamilyChains() {
            return familyChains;
        }

        /** ISO 15924 script → the chain for text in that script. */
        public Map<String, List<String>> getScriptChains() {
            return scriptChains;
        }
    }

    /** One link in a fallback chain. */
    public static class SubstitutionRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String scope;
        @NotBlank
        private String scopeKey;
        private int position;
        @NotBlank
        private String fallbackFamily;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        /** FAMILY or SCRIPT. */
        public String getScope() {
            return scope;
        }

        public void setScope(String value) {
            this.scope = value;
        }

        /** The family being substituted, or the ISO 15924 script code. */
        public String getScopeKey() {
            return scopeKey;
        }

        public void setScopeKey(String value) {
            this.scopeKey = value;
        }

        /** Order within the chain. The client's preference, kept. */
        public int getPosition() {
            return position;
        }

        public void setPosition(int value) {
            this.position = value;
        }

        public String getFallbackFamily() {
            return fallbackFamily;
        }

        public void setFallbackFamily(String value) {
            this.fallbackFamily = value;
        }
    }

    @GetMapping("/licence-acknowledgement")
    @Operation(summary = "The words an uploader must agree to",
            description = "Fetch, display in full — not as fine print — and echo back on upload. "
                    + "An upload whose text does not match this is refused, so that what gets "
                    + "recorded is provably what was shown.")
    public ResponseEntity<Object> wording() {
        current.require();
        return ResponseEntity.ok((Object) new AcknowledgementWording());
    }

    @GetMapping
    @Operation(summary = "List installed fonts",
            description = "Requires documents.font:read. Family, style, script coverage, source, "
                    + "uploader and licence metadata, plus the three consumer views — the "
                    + "fontconfig filename, the @font-face and the mdv pdf.fonts entry — derived "
                    + "from the one record so they cannot disagree.")
    public ResponseEntity<Object> list(@RequestParam("companyId") String companyId,
            @RequestParam(name = "workerFontDirectory", required = false) String workerFontDirectory,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, companyId, DocumentPermissions.FONT_READ,
                BusinessInstants.date(businessDate), "listing fonts");

        final String directory = workerFontDirectory;
        return ResponseEntity.ok((Object) com.coreintra.app.api.documents.DocumentPages.page(
                fonts.installedFor(companyId), InstalledFontView.KEYS,
                new java.util.function.Function<InstalledFont, InstalledFontView>() {
                    @Override
                    public InstalledFontView apply(InstalledFont font) {
                        return new InstalledFontView(font, directory);
                    }
                }, cursor, limit));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Install a client font",
            description = "Requires documents.font:install. The upload must carry "
                    + "licenceAcknowledged=true, the current acknowledgement version, and the "
                    + "acknowledgement text echoed back exactly; any of the three missing is a "
                    + "refusal, because an acknowledgement that can be omitted is not one. Who "
                    + "agreed, when, and the exact wording are recorded on the font row.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = InstalledFontView.class)))
    public ResponseEntity<Object> install(
            @RequestPart("file") MultipartFile file,
            @RequestParam("companyId") String companyId,
            @RequestParam("family") String family,
            @RequestParam(name = "style", required = false) String style,
            @RequestParam("fileFormat") String fileFormat,
            @RequestParam(name = "scriptCoverage", required = false) List<String> scriptCoverage,
            @RequestParam(name = "licenceAcknowledged", required = false) Boolean acknowledged,
            @RequestParam(name = "licenceAcknowledgementVersion", required = false) String version,
            @RequestParam(name = "licenceAcknowledgementText", required = false) String text,
            @RequestParam(name = "embeddingPermission", required = false) String embedding,
            @RequestParam(name = "fsTypeRaw", required = false) Integer fsTypeRaw,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, companyId, DocumentPermissions.FONT_INSTALL,
                BusinessInstants.date(businessDate), "installing font " + family);

        if (acknowledged == null || !acknowledged.booleanValue()) {
            return DocumentProblems.unprocessable("licence_not_acknowledged",
                    "The licence acknowledgement is required",
                    "Send licenceAcknowledged=true together with the acknowledgement text. This "
                            + "is not a formality: we do not verify font licences and provide no "
                            + "indemnity, so the installation must record that somebody accepted "
                            + "that. An acknowledgement that could be omitted, or that defaulted "
                            + "to true, would record nothing.");
        }
        if (!LicenceAcknowledgement.VERSION.equals(Texts.strip(String.valueOf(version)))) {
            return DocumentProblems.unprocessable("licence_wording_stale",
                    "The acknowledgement wording has changed",
                    "This client acknowledged version \"" + version + "\" but the current wording "
                            + "is \"" + LicenceAcknowledgement.VERSION + "\". Fetch "
                            + "/api/v1/fonts/licence-acknowledgement, show the new words, and ask "
                            + "again — an agreement to wording nobody read is not an agreement.");
        }
        if (!LicenceAcknowledgement.matches(text)) {
            return DocumentProblems.unprocessable("licence_text_mismatch",
                    "The acknowledgement text does not match",
                    "The text echoed back is not the text this server displays. What gets "
                            + "recorded has to be provably what was shown, so an approximate "
                            + "echo is refused rather than stored.");
        }

        String oversize = Uploads.tooLarge(file, Uploads.FONT_MAX_BYTES, "font");
        if (oversize != null) {
            return DocumentProblems.tooLarge(oversize);
        }

        FontEntity installed = fonts.installClientFont(companyId, family, style, fileFormat,
                Uploads.bytes(file, "font"), Uploads.name(file), scripts(scriptCoverage),
                declaration(embedding, fsTypeRaw), caller.accountId(),
                LicenceAcknowledgement.recordedText());

        InstalledFontView view = viewOf(companyId, installed.id(), null);
        return view == null
                ? DocumentProblems.notFound("font", installed.id())
                : ResponseEntity.status(HttpStatus.CREATED).body((Object) view);
    }

    @PostMapping("/{fontId}/disable")
    @Operation(summary = "Disable a font without removing it",
            description = "Requires documents.font:install. A disabled font stays installed and "
                    + "stops being resolved to, which is the reversible half of removal and the "
                    + "right first move when a licence is in question.")
    public ResponseEntity<Object> disable(@PathVariable String fontId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<FontEntity> font = access.font(caller, fontId, DocumentPermissions.FONT_INSTALL,
                BusinessInstants.date(businessDate), "disabling font " + fontId);
        if (!font.isPresent()) {
            return DocumentProblems.notFound("font", fontId);
        }
        FontEntity disabled = fonts.disable(fontId, OffsetDateTime.now());
        InstalledFontView view = viewOf(disabled.companyId(), fontId, null);
        return ResponseEntity.ok((Object) (view == null ? new Object() : view));
    }

    @PostMapping("/{fontId}/enable")
    @Operation(summary = "Re-enable a disabled font",
            description = "Requires documents.font:install.")
    public ResponseEntity<Object> enable(@PathVariable String fontId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<FontEntity> font = access.font(caller, fontId, DocumentPermissions.FONT_INSTALL,
                BusinessInstants.date(businessDate), "enabling font " + fontId);
        if (!font.isPresent()) {
            return DocumentProblems.notFound("font", fontId);
        }
        FontEntity enabled = fonts.enable(fontId);
        InstalledFontView view = viewOf(enabled.companyId(), fontId, null);
        return ResponseEntity.ok((Object) (view == null ? new Object() : view));
    }

    @GetMapping("/{fontId}/removal-impact")
    @Operation(summary = "How many documents a removal would affect",
            description = "Requires documents.font:read. Ask this before removing: the count is "
                    + "the number of archived renders that used the font, and removing it means "
                    + "those documents re-render in a different face. The removal itself has to "
                    + "state this number back.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = RemovalImpactView.class)))
    public ResponseEntity<Object> removalImpact(@PathVariable String fontId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<FontEntity> font = access.font(caller, fontId, DocumentPermissions.FONT_READ,
                BusinessInstants.date(businessDate), "removal impact for font " + fontId);
        if (!font.isPresent()) {
            return DocumentProblems.notFound("font", fontId);
        }
        return ResponseEntity.ok(
                (Object) new RemovalImpactView(fonts.impactOfRemoving(fontId)));
    }

    @DeleteMapping("/{fontId}")
    @Operation(summary = "Remove a font",
            description = "Requires documents.font:remove and acknowledgedAffectedCount matching "
                    + "the count from removal-impact. Refused with a 409 if the count moved while "
                    + "the user was reading it. The row is retired rather than deleted: an "
                    + "archived render that used this font must keep resolving what it used.")
    public ResponseEntity<Object> remove(@PathVariable String fontId,
            @RequestParam("acknowledgedAffectedCount") long acknowledgedAffectedCount,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<FontEntity> font = access.font(caller, fontId, DocumentPermissions.FONT_REMOVE,
                BusinessInstants.date(businessDate), "removing font " + fontId);
        if (!font.isPresent()) {
            return DocumentProblems.notFound("font", fontId);
        }
        try {
            fonts.remove(fontId, acknowledgedAffectedCount, OffsetDateTime.now());
        } catch (IllegalStateException moved) {
            return DocumentProblems.conflict("affected_count_changed",
                    "The number of affected documents changed", moved.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/substitutions")
    @Operation(summary = "The substitution map",
            description = "Requires documents.font:read. The client's own family and script "
                    + "chains over the shipped defaults. A client rule for a family replaces the "
                    + "shipped chain rather than appending to it.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = SubstitutionMapView.class)))
    public ResponseEntity<Object> substitutions(@RequestParam("companyId") String companyId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, companyId, DocumentPermissions.FONT_READ,
                BusinessInstants.date(businessDate), "reading the substitution map");
        return ResponseEntity.ok(
                (Object) new SubstitutionMapView(fonts.substitutionMapFor(companyId)));
    }

    @PostMapping("/substitutions")
    @Operation(summary = "Add a link to a fallback chain",
            description = "Requires documents.font:install. Position is the client's preference "
                    + "and is kept: the chain is tried in order.")
    public ResponseEntity<Object> addSubstitution(@Valid @RequestBody SubstitutionRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, body.getCompanyId(), DocumentPermissions.FONT_INSTALL,
                BusinessInstants.date(businessDate), "editing the substitution map");

        FontSubstitutionRuleEntity.Scope scope = scope(body.getScope());
        fonts.addSubstitution(body.getCompanyId(), scope, body.getScopeKey(), body.getPosition(),
                body.getFallbackFamily());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                (Object) new SubstitutionMapView(fonts.substitutionMapFor(body.getCompanyId())));
    }

    @GetMapping("/resolve")
    @Operation(summary = "What these families would actually resolve to",
            description = "Requires documents.font:read. Every requested family is reported, not "
                    + "only the failures: a missing font must produce a named warning and a "
                    + "recorded substitution rather than a silent fallback, and a client that "
                    + "only ever sees warnings cannot tell \"checked and fine\" from \"not "
                    + "checked\".")
    public ResponseEntity<Object> resolve(@RequestParam("companyId") String companyId,
            @RequestParam("families") List<String> families,
            @RequestParam(name = "script", required = false) String script,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, companyId, DocumentPermissions.FONT_READ,
                BusinessInstants.date(businessDate), "resolving font families");

        FontResolver resolver = fonts.resolverFor(companyId);
        List<FontWarningView> resolutions = new ArrayList<FontWarningView>();
        for (String family : families) {
            if (Texts.isBlank(family)) {
                continue;
            }
            resolutions.add(new FontWarningView(
                    resolver.resolve(Texts.strip(family), Texts.isBlank(script) ? null : script)));
        }
        return ResponseEntity.ok((Object) Immutables.copyOf(resolutions));
    }

    /**
     * Re-reads the row through the store so the response carries the three
     * consumer views, which are derived from the record rather than stored.
     */
    private InstalledFontView viewOf(String companyId, String fontId, String workerFontDirectory) {
        for (InstalledFont font : fonts.installedFor(companyId)) {
            if (font.id().equals(fontId)) {
                return new InstalledFontView(font, workerFontDirectory);
            }
        }
        return null;
    }

    private static Set<String> scripts(List<String> requested) {
        Set<String> scripts = new LinkedHashSet<String>();
        if (requested != null) {
            for (String script : requested) {
                if (!Texts.isBlank(script)) {
                    scripts.add(Texts.strip(script));
                }
            }
        }
        return scripts;
    }

    /**
     * The embedding permission the client read out of the font.
     *
     * <p>Taken from the request rather than parsed here: reading the OS/2 table
     * means a font parser, and no font type may appear outside the documents
     * module. When the client says nothing this records UNKNOWN, which is the
     * honest value — "we did not read it" and "it says installable" must not look
     * the same in the manager.
     */
    private static EmbeddingDeclaration declaration(String requested, Integer fsTypeRaw) {
        if (Texts.isBlank(requested)) {
            return EmbeddingDeclaration.unreadable();
        }
        String wanted = Texts.strip(requested).toUpperCase(Locale.ROOT);
        for (FontRecord.EmbeddingPermission candidate
                : FontRecord.EmbeddingPermission.values()) {
            if (candidate.name().equals(wanted)) {
                return new EmbeddingDeclaration(candidate, fsTypeRaw);
            }
        }
        throw new IllegalArgumentException("\"" + requested + "\" is not an embedding permission. "
                + "It is INSTALLABLE, RESTRICTED, PRINT_AND_PREVIEW, EDITABLE or UNKNOWN, read "
                + "from the font's own OS/2 table.");
    }

    private static FontSubstitutionRuleEntity.Scope scope(String requested) {
        String wanted = Texts.isBlank(requested)
                ? "" : Texts.strip(requested).toUpperCase(Locale.ROOT);
        for (FontSubstitutionRuleEntity.Scope candidate
                : FontSubstitutionRuleEntity.Scope.values()) {
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("\"" + requested + "\" is not a substitution scope. A "
                + "rule is scoped to a FAMILY or to a SCRIPT.");
    }
}
