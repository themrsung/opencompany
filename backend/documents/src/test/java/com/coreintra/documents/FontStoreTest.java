package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.font.FontSubstitutionMap;
import com.coreintra.documents.render.RenderMetadata;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The font store, and the failure it exists to prevent.
 *
 * <p>Measured on this build: {@code fc-match "Pretendard"} returns DejaVu Sans,
 * which has no Korean coverage whatsoever, and says nothing about it. That is
 * the default behaviour of the layer underneath us, so "a missing font produces
 * a named warning and a recorded substitution, never a silent fallback" is a
 * property this code has to add, not one it can assume.
 */
class FontStoreTest {

    private static final String KOREAN = "Hang";
    private static final String LATIN = "Latn";
    private static final String ARABIC = "Arab";

    private static FontRecord bundled(String family, String... scripts) {
        return FontRecord.builder("id-" + family, family)
                .source(FontRecord.Source.BUNDLED)
                .scriptCoverage(Immutables.setOfArray(scripts))
                .blobSha256("hash-" + family)
                .build();
    }

    private static List<FontRecord> installed(FontRecord... records) {
        List<FontRecord> list = new ArrayList<FontRecord>();
        for (FontRecord record : records) {
            list.add(record);
        }
        return list;
    }

    @Nested
    @DisplayName("substitution is never silent")
    class NeverSilent {

        /** ACCEPTANCE: a missing font warns and records; it never falls back quietly. */
        @Test
        @DisplayName("a missing family produces a named warning and a named substitute")
        void missingFamilyIsNamed() {
            FontResolver resolver = new FontResolver(
                    installed(bundled("Noto Sans CJK KR", KOREAN, LATIN)),
                    FontSubstitutionMap.shippedDefault());

            FontResolver.Resolution resolution = resolver.resolve("함초롬바탕", KOREAN);

            assertThat(resolution.isSubstituted()).isTrue();
            assertThat(resolution.resolvedFamily()).isEqualTo("Noto Sans CJK KR");
            assertThat(resolution.reason())
                    .as("the warning must name BOTH the missing family and the substitute")
                    .contains("함초롬바탕")
                    .contains("Noto Sans CJK KR")
                    .contains("will not look as the template intended");
        }

        @Test
        @DisplayName("an installed family that cannot render the script still substitutes")
        void installedButNoCoverage() {
            // The exact shape of the fc-match "Pretendard" -> DejaVu Sans trap:
            // a font that exists but has no Korean in it.
            FontResolver resolver = new FontResolver(
                    installed(bundled("DejaVu Sans", LATIN),
                              bundled("Noto Sans CJK KR", KOREAN, LATIN)),
                    FontSubstitutionMap.shippedDefault());

            FontResolver.Resolution resolution = resolver.resolve("DejaVu Sans", KOREAN);

            assertThat(resolution.isSubstituted())
                    .as("present is not the same as usable")
                    .isTrue();
            assertThat(resolution.resolvedFamily()).isEqualTo("Noto Sans CJK KR");
            assertThat(resolution.reason()).contains("does not cover Hang");
        }

        @Test
        @DisplayName("when nothing can render the script, that is said outright")
        void unresolvableIsReported() {
            FontResolver resolver = new FontResolver(
                    installed(bundled("DejaVu Sans", LATIN)),
                    FontSubstitutionMap.shippedDefault());

            FontResolver.Resolution resolution = resolver.resolve("Amiri", ARABIC);

            assertThat(resolution.isUnresolved()).isTrue();
            assertThat(resolution.reason())
                    .contains("no fallback covers Arab")
                    .contains("Install the font");
        }

        @Test
        @DisplayName("an installed family that covers the script is not reported as substituted")
        void cleanResolutionIsQuiet() {
            // Warning about a font that was used exactly as asked would train
            // users to ignore the warnings that matter.
            FontResolver resolver = new FontResolver(
                    installed(bundled("Pretendard", KOREAN, LATIN)),
                    FontSubstitutionMap.shippedDefault());

            FontResolver.Resolution resolution = resolver.resolve("Pretendard", KOREAN);
            assertThat(resolution.isSubstituted()).isFalse();
            assertThat(resolver.warnings(
                    resolver.resolveAll(Immutables.setOf("Pretendard"), KOREAN))).isEmpty();
        }

        @Test
        @DisplayName("family names match regardless of case, spaces and hyphens")
        void familyMatchingIsForgiving() {
            // Documents in the wild spell the same family three ways. Treating
            // them as different fonts would report substitutions that are not
            // happening.
            FontResolver resolver = new FontResolver(
                    installed(bundled("Noto Sans CJK KR", KOREAN)),
                    FontSubstitutionMap.shippedDefault());

            assertThat(resolver.resolve("notosanscjkkr", KOREAN).isSubstituted()).isFalse();
            assertThat(resolver.resolve("Noto-Sans-CJK-KR", KOREAN).isSubstituted()).isFalse();
            assertThat(resolver.resolve("NOTO SANS CJK KR", KOREAN).isSubstituted()).isFalse();
        }
    }

    @Nested
    @DisplayName("licensing acknowledgement")
    class Licensing {

        @Test
        @DisplayName("a client upload without a recorded acknowledgement is refused")
        void acknowledgementIsMandatory() {
            assertThatThrownBy(() -> FontRecord.builder("f1", "SomeClientFont")
                    .source(FontRecord.Source.CLIENT_UPLOADED)
                    .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no evidence anyone took responsibility");
        }

        @Test
        @DisplayName("the accepted wording is stored verbatim, not just a boolean")
        void wordingIsStored() {
            String wording = "본 폰트를 설치 및 임베딩할 권리를 보유하고 있음을 확인합니다. "
                    + "제공자는 이를 검증하지 않으며 어떠한 보증도 하지 않습니다.";
            FontRecord record = FontRecord.builder("f1", "ClientFont")
                    .source(FontRecord.Source.CLIENT_UPLOADED)
                    .acknowledgedBy("acc-1", OffsetDateTime.now(), wording)
                    .embeddingPermission(FontRecord.EmbeddingPermission.RESTRICTED)
                    .build();

            // A boolean records that someone clicked. The text records what they
            // agreed to, which is what an argument years later turns on.
            assertThat(record.licenceAcknowledgementText()).isEqualTo(wording);
            assertThat(record.uploadedByAccountId()).isEqualTo("acc-1");
            assertThat(record.embeddingPermission())
                    .as("the font's own fsType is surfaced read-only beside the checkbox")
                    .isEqualTo(FontRecord.EmbeddingPermission.RESTRICTED);
        }

        @Test
        @DisplayName("bundled fonts need no acknowledgement, because we hold the rights")
        void bundledNeedsNoAcknowledgement() {
            assertThat(bundled("Pretendard", KOREAN, LATIN).source())
                    .isEqualTo(FontRecord.Source.BUNDLED);
        }
    }

    @Nested
    @DisplayName("the shipped substitution defaults")
    class ShippedDefaults {

        @Test
        @DisplayName("Hancom faces are mapped rather than bundled")
        void hancomFacesAreMapped() {
            // 함초롬바탕/함초롬돋움 are Hancom-licensed and must never ship with
            // the product. Mapping them is the supported answer.
            FontSubstitutionMap map = FontSubstitutionMap.shippedDefault();
            assertThat(map.chainFor("함초롬바탕", KOREAN)).contains("Pretendard");
            assertThat(map.chainFor("함초롬돋움", KOREAN)).contains("Pretendard");
        }

        @Test
        @DisplayName("Pretendard leads the Korean and Latin chains")
        void pretendardLeads() {
            FontSubstitutionMap map = FontSubstitutionMap.shippedDefault();
            assertThat(map.chainFor("anything", KOREAN).get(0)).isEqualTo("Pretendard");
            assertThat(map.chainFor("anything", LATIN).get(0)).isEqualTo("Pretendard");
        }

        @Test
        @DisplayName("family-specific mappings take precedence over the script default")
        void familyBeatsScript() {
            FontSubstitutionMap map = FontSubstitutionMap.builder()
                    .mapFamily("바탕", "Noto Serif CJK KR")
                    .mapScript(KOREAN, "Pretendard")
                    .build();
            assertThat(map.chainFor("바탕", KOREAN))
                    .containsExactly("Noto Serif CJK KR", "Pretendard");
        }
    }

    @Nested
    @DisplayName("render metadata")
    class Metadata {

        @Test
        @DisplayName("a render without a recorded renderer version is refused")
        void rendererVersionRequired() {
            assertThatThrownBy(() -> RenderMetadata.builder().build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reproducibility contract");
        }

        @Test
        @DisplayName("substitutions are carried into the metadata, not just warned about")
        void substitutionsAreRecorded() {
            FontResolver resolver = new FontResolver(
                    installed(bundled("Noto Sans CJK KR", KOREAN)),
                    FontSubstitutionMap.shippedDefault());
            Set<String> requested = Immutables.setOf("함초롬바탕", "Noto Sans CJK KR");

            RenderMetadata metadata = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("t-leave", 3)
                    .font("Noto Sans CJK KR", "hash-Noto Sans CJK KR")
                    .fontResolutions(resolver.resolveAll(requested, KOREAN))
                    .locale("ko")
                    .build();

            assertThat(metadata.hadSubstitutions()).isTrue();
            assertThat(metadata.substitutions())
                    .hasSize(1)
                    .anyMatch(s -> s.contains("함초롬바탕"));
        }

        @Test
        @DisplayName("a later render that differs is explained, cause by cause")
        void explainsDifferences() {
            RenderMetadata archived = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("t-leave", 3)
                    .font("Pretendard", "hash-a")
                    .locale("ko")
                    .build();

            RenderMetadata regenerated = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 25.2.0.1")
                    .template("t-leave", 4)
                    .font("Noto Sans CJK KR", "hash-b")
                    .locale("ko")
                    .build();

            List<String> differences = archived.explainDifferenceFrom(regenerated);

            assertThat(differences)
                    .anyMatch(d -> d.contains("renderer changed"))
                    .anyMatch(d -> d.contains("template version changed"))
                    .anyMatch(d -> d.contains("font no longer present: Pretendard"))
                    .anyMatch(d -> d.contains("font added: Noto Sans CJK KR"));
        }

        @Test
        @DisplayName("identical inputs explain nothing, so a byte difference is genuinely unexplained")
        void identicalInputsExplainNothing() {
            RenderMetadata one = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("t", 1).font("Pretendard", "h").locale("ko").build();
            RenderMetadata two = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("t", 1).font("Pretendard", "h").locale("ko").build();

            assertThat(one.explainDifferenceFrom(two)).isEmpty();
        }
    }
}
