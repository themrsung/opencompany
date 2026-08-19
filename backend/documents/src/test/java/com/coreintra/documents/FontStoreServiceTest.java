package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.render.RenderMetadata;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.EmbeddingDeclaration;
import com.coreintra.documents.service.FontRemovalImpact;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.InstalledFont;
import com.coreintra.documents.service.RenderService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.InMemoryBlobStore;

/**
 * The font store, and the two things it must never do quietly.
 *
 * <p>It must never accept a client's font without recording who took responsibility for
 * the licence, and it must never substitute one font for another without saying so. Both
 * are named requirements (brief 6.9), and both fail silently by default in the layer
 * underneath: fontconfig answers every question, and a checkbox with no row behind it
 * proves nothing.
 */
class FontStoreServiceTest {

    private static final String COMPANY = "company-1";
    private static final String ACCOUNT = "account-1";
    private static final String KOREAN = "Hang";
    private static final String ARABIC = "Arab";

    private static final String ACKNOWLEDGEMENT =
            "이 글꼴을 설치·임베드할 권리를 보유하고 있음을 확인하며, 공급자는 이를 검증하거나 "
            + "면책하지 않습니다. (I warrant that this organisation holds the rights to install "
            + "and embed this font. CoreIntra neither verifies nor indemnifies, and the font's "
            + "own embedding restrictions remain ours to honour.)";

    private Fakes.Fonts fontRows;
    private Fakes.Renders renderRows;
    private FontStoreService fonts;
    private RenderService renders;

    @BeforeEach
    void setUp() {
        fontRows = new Fakes.Fonts();
        renderRows = new Fakes.Renders();
        BlobService blobs = new BlobService(new InMemoryBlobStore(), new Fakes.Blobs());
        fonts = new FontStoreService(fontRows, new Fakes.Substitutions(), renderRows, blobs);
        renders = new RenderService(renderRows, blobs);
    }

    private static byte[] fakeFontFile(String family) {
        return ("OTTO-" + family).getBytes(StandardCharsets.UTF_8);
    }

    private FontEntity installKorean(String family) {
        return fonts.registerBundledFont(family, "Regular", "otf", fakeFontFile(family),
                family + ".otf", Immutables.setOf(KOREAN, "Latn"));
    }

    @Nested
    @DisplayName("a licence acknowledgement is recorded or the font is not installed")
    class LicenceAcknowledgement {

        @Test
        @DisplayName("a client upload records the exact words the uploader accepted")
        void theWordsAreStoredVerbatim() {
            FontEntity font = fonts.installClientFont(COMPANY, "함초롬바탕", "Regular", "ttf",
                    fakeFontFile("함초롬바탕"), "HCRBatang.ttf", Immutables.setOf(KOREAN),
                    new EmbeddingDeclaration(FontRecord.EmbeddingPermission.PRINT_AND_PREVIEW,
                            Integer.valueOf(4)),
                    ACCOUNT, ACKNOWLEDGEMENT);

            assertThat(font.licenceAcknowledgementText())
                    .describedAs("verbatim: a paraphrase is not what they agreed to")
                    .isEqualTo(ACKNOWLEDGEMENT);
            assertThat(font.uploadedByAccountId()).isEqualTo(ACCOUNT);
            assertThat(font.uploadedAt()).isNotNull();
            assertThat(font.embeddingPermission())
                    .describedAs("read from the font and shown beside the box, never enforced by us")
                    .isEqualTo(FontRecord.EmbeddingPermission.PRINT_AND_PREVIEW);
            assertThat(font.fsTypeRaw())
                    .describedAs("the font's own bits, kept beside our reading of them")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("a client upload with no acknowledgement is refused and nothing is stored")
        void anUnacknowledgedUploadIsRefused() {
            assertThatThrownBy(() -> fonts.installClientFont(COMPANY, "함초롬바탕", "Regular", "ttf",
                    fakeFontFile("함초롬바탕"), "HCRBatang.ttf", Immutables.setOf(KOREAN),
                    EmbeddingDeclaration.unreadable(), ACCOUNT, "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("acknowledgement");

            assertThat(fontRows.count()).isZero();
        }

        @Test
        @DisplayName("a bundled font needs no acknowledgement because we hold the rights")
        void bundledFontsAreOurs() {
            FontEntity font = installKorean("Pretendard");

            assertThat(font.source()).isEqualTo(FontRecord.Source.BUNDLED);
            assertThat(font.companyId())
                    .describedAs("bundled fonts belong to no client and serve all of them")
                    .isNull();
            assertThat(font.licenceAcknowledgementText()).isNull();
        }

        @Test
        @DisplayName("installing the same family and style twice is refused")
        void oneRowPerFamilyAndStyle() {
            installKorean("Pretendard");

            assertThatThrownBy(() -> installKorean("Pretendard"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already installed");
        }
    }

    @Nested
    @DisplayName("substitution is named and recorded, never silent")
    class NeverSilent {

        /**
         * ACCEPTANCE: a missing font produces a named warning and a recorded substitution.
         *
         * <p>The warning names both families, and the substitution reaches the render row -
         * so the record survives the request that made it, which is the half that matters
         * when somebody asks two years later why the PDF looks wrong.
         */
        @Test
        @DisplayName("a missing family warns by name and the substitution reaches the render row")
        void aMissingFamilyIsNamedAndRecorded() {
            installKorean("Noto Sans CJK KR");
            FontResolver resolver = fonts.resolverFor(COMPANY);

            List<FontResolver.Resolution> resolutions =
                    resolver.resolveAll(Immutables.setOf("Pretendard"), KOREAN);
            List<FontResolver.Resolution> warnings = resolver.warnings(resolutions);

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).isSubstituted()).isTrue();
            assertThat(warnings.get(0).reason())
                    .describedAs("names what was asked for and what was used instead")
                    .contains("Pretendard")
                    .contains("Noto Sans CJK KR");

            RenderMetadata metadata = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .fontResolutions(resolutions)
                    .font("Noto Sans CJK KR", "hash-noto")
                    .locale("ko")
                    .build();
            byte[] pdf = "%PDF-1.7 substituted".getBytes(StandardCharsets.UTF_8);

            com.coreintra.documents.entity.DocumentRenderEntity archived = renders.archive(COMPANY,
                    "doc-1", 1, RenderFormat.PDF, metadata, pdf, "application/pdf");

            assertThat(archived.hadSubstitutions()).isTrue();
            assertThat(archived.substitutions())
                    .contains("Pretendard")
                    .contains("Noto Sans CJK KR");
        }

        @Test
        @DisplayName("a clean render records no substitutions, so the flag means something")
        void aCleanRenderRecordsNothing() {
            installKorean("Pretendard");
            FontResolver resolver = fonts.resolverFor(COMPANY);

            List<FontResolver.Resolution> resolutions =
                    resolver.resolveAll(Immutables.setOf("Pretendard"), KOREAN);

            assertThat(resolver.warnings(resolutions)).isEmpty();

            com.coreintra.documents.entity.DocumentRenderEntity archived = renders.archive(COMPANY,
                    "doc-2", 1, RenderFormat.PDF,
                    RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2")
                            .fontResolutions(resolutions).build(),
                    "%PDF-1.7 clean".getBytes(StandardCharsets.UTF_8), "application/pdf");

            assertThat(archived.hadSubstitutions()).isFalse();
        }

        @Test
        @DisplayName("a script nothing covers is reported as unresolved rather than as a substitute")
        void anUncoverableScriptIsNotPretendedAway() {
            installKorean("Pretendard");
            FontResolver resolver = fonts.resolverFor(COMPANY);

            FontResolver.Resolution resolution = resolver.resolve("Amiri", ARABIC);

            assertThat(resolution.isUnresolved())
                    .describedAs("no Arabic face is installed, and a Korean one is not a substitute")
                    .isTrue();
            assertThat(resolution.reason()).contains("Amiri").contains(ARABIC);
        }

        @Test
        @DisplayName("a client substitution rule replaces the shipped chain for that family")
        void clientRulesWin() {
            installKorean("Noto Sans CJK KR");
            fonts.registerBundledFont("Client Myeongjo", "Regular", "otf",
                    fakeFontFile("Client Myeongjo"), "cm.otf", Immutables.setOf(KOREAN));
            fonts.addSubstitution(COMPANY,
                    com.coreintra.documents.entity.FontSubstitutionRuleEntity.Scope.FAMILY,
                    "함초롬바탕", 0, "Client Myeongjo");

            FontResolver.Resolution resolution =
                    fonts.resolverFor(COMPANY).resolve("함초롬바탕", KOREAN);

            assertThat(resolution.resolvedFamily()).isEqualTo("Client Myeongjo");
            assertThat(resolution.isSubstituted()).isTrue();
        }
    }

    @Nested
    @DisplayName("removal warns with the affected count first")
    class Removal {

        @Test
        @DisplayName("the count of archived renders using the font is shown in both languages")
        void theWarningNamesTheCost() {
            FontEntity font = installKorean("Pretendard");
            renders.archive(COMPANY, "doc-1", 1, RenderFormat.PDF,
                    RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2")
                            .font("Pretendard", "hash-pretendard").build(),
                    "%PDF-1.7".getBytes(StandardCharsets.UTF_8), "application/pdf");

            FontRemovalImpact impact = fonts.impactOfRemoving(font.id());

            assertThat(impact.affectedRenderCount()).isEqualTo(1);
            assertThat(impact.isSafe()).isFalse();
            assertThat(impact.warningKo()).contains("Pretendard").contains("1");
            assertThat(impact.warningEn()).contains("Pretendard").contains("reproduced");
        }

        @Test
        @DisplayName("removing with a count the caller never saw is refused")
        void aStaleWarningIsNotAWarning() {
            FontEntity font = installKorean("Pretendard");
            renders.archive(COMPANY, "doc-1", 1, RenderFormat.PDF,
                    RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2")
                            .font("Pretendard", "hash-pretendard").build(),
                    "%PDF-1.7".getBytes(StandardCharsets.UTF_8), "application/pdf");

            assertThatThrownBy(() -> fonts.remove(font.id(), 0L, OffsetDateTime.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("changed from 0 to 1");
        }

        @Test
        @DisplayName("a removed font is retired, not deleted, and stops being offered")
        void removalIsRetirement() {
            FontEntity font = installKorean("Pretendard");

            fonts.remove(font.id(), 0L, OffsetDateTime.now());

            assertThat(fontRows.findById(font.id()))
                    .describedAs("the row stays: an archived render's font set names this hash")
                    .isPresent();
            assertThat(fonts.installedFor(COMPANY)).isEmpty();
        }

        @Test
        @DisplayName("disabling stops a font being offered without retiring it")
        void disablingIsReversible() {
            FontEntity font = installKorean("Pretendard");

            fonts.disable(font.id(), OffsetDateTime.now());
            List<InstalledFont> stillListed = fonts.installedFor(COMPANY);

            assertThat(stillListed).hasSize(1);
            assertThat(stillListed.get(0).isEnabled()).isFalse();
            assertThat(fonts.resolverFor(COMPANY).installedFamilies())
                    .describedAs("a disabled font is not a candidate for resolution")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("one store, three consumers")
    class ThreeConsumers {

        /**
         * If any two of the three disagree about what a font looks like, the feature is
         * broken - WYSIWYG is the whole point. They cannot disagree here because all three
         * are derived from one row.
         */
        @Test
        @DisplayName("fontconfig, the webfont route and mdv all name the same bytes")
        void allThreeViewsComeFromOneRow() {
            FontEntity font = installKorean("Pretendard");
            InstalledFont installed = fonts.installedFor(COMPANY).get(0);

            assertThat(installed.fontconfigFileName()).isEqualTo("Pretendard-Regular.otf");
            assertThat(installed.webfontFaceCss())
                    .contains("font-family:\"Pretendard\"")
                    .contains(font.blobSha256())
                    .contains("opentype");
            assertThat(installed.mdvFontConfigEntry("/var/lib/coreintra/fonts"))
                    .contains("\"family\": \"Pretendard\"")
                    .contains("/var/lib/coreintra/fonts/Pretendard-Regular.otf")
                    .contains("\"subset\": true");
        }

        @Test
        @DisplayName("the file name comes from the family, not from the uploaded filename")
        void uploadedFilenamesAreNotTrusted() {
            fonts.installClientFont(COMPANY, "Noto Naskh Arabic", "Bold", "ttf",
                    fakeFontFile("naskh"), "../../etc/fonts/evil.ttf",
                    Immutables.setOf(ARABIC),
                    new EmbeddingDeclaration(FontRecord.EmbeddingPermission.INSTALLABLE,
                            Integer.valueOf(0)),
                    ACCOUNT, ACKNOWLEDGEMENT);

            InstalledFont installed = fonts.installedFor(COMPANY).get(0);

            assertThat(installed.fontconfigFileName()).isEqualTo("Noto_Naskh_Arabic-Bold.ttf");
            assertThat(installed.fontconfigFileName()).doesNotContain("..").doesNotContain("/");
        }
    }
}
