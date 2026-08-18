package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.render.RenderMetadata;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.RenderFingerprint;
import com.coreintra.documents.service.RenderService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.InMemoryBlobStore;

/**
 * The archived render is authoritative, and the schema has to make that checkable.
 *
 * <p>The LibreOffice path cannot be made byte-deterministic, so brief 6.4 settles it by
 * fiat: if a regenerated PDF fails to match the archived one, the archived one is what was
 * approved. That only means something if the archive can be found by configuration and if
 * a difference can be explained. Both are asserted here.
 */
class RenderArchiveTest {

    private static final String COMPANY = "company-1";
    private static final String DOCUMENT = "doc-1";
    private static final byte[] PDF = "%PDF-1.7 approved".getBytes(StandardCharsets.UTF_8);

    private Fakes.Renders renderRows;
    private RenderService renders;

    @BeforeEach
    void setUp() {
        renderRows = new Fakes.Renders();
        renders = new RenderService(renderRows,
                new BlobService(new InMemoryBlobStore(), new Fakes.Blobs()));
    }

    private static RenderMetadata metadata(String rendererVersion) {
        return RenderMetadata.builder()
                .rendererVersion(rendererVersion)
                .template("template-1", 3)
                .font("Pretendard", "hash-pretendard")
                .font("Noto Sans CJK KR", "hash-noto")
                .locale("ko")
                .extra("mdv.buildTime", "2026-08-18T09:00:00.000")
                .renderedAt(OffsetDateTime.parse("2026-08-18T00:00:00Z"))
                .build();
    }

    @Nested
    @DisplayName("the archive is preferred to the renderer")
    class PreferTheArchive {

        @Test
        @DisplayName("the same configuration finds the archived render rather than making another")
        void sameConfigurationHits() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            Optional<DocumentRenderEntity> found = renders.findArchived(DOCUMENT, 1,
                    RenderFormat.PDF, metadata("LibreOffice 24.2.7.2"));

            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(archived.id());
            assertThat(renders.outputOf(found.get())).isEqualTo(PDF);
        }

        @Test
        @DisplayName("archiving the same render twice keeps one row")
        void archivingIsIdempotent() {
            DocumentRenderEntity first = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");
            DocumentRenderEntity second = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(renderRows.count())
                    .describedAs("two archives of one thing leaves a later reader choosing")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a LibreOffice upgrade is a different configuration and does not hit")
        void anUpgradeIsADifferentConfiguration() {
            renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            assertThat(renders.findArchived(DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 25.2.0.1"))).isEmpty();
        }

        @Test
        @DisplayName("a changed font set is a different configuration")
        void aChangedFontSetIsADifferentConfiguration() {
            RenderMetadata original = metadata("LibreOffice 24.2.7.2");
            RenderMetadata withNewFont = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("template-1", 3)
                    .font("Pretendard", "hash-pretendard-v2")
                    .font("Noto Sans CJK KR", "hash-noto")
                    .locale("ko")
                    .extra("mdv.buildTime", "2026-08-18T09:00:00.000")
                    .build();

            assertThat(RenderFingerprint.of(RenderFormat.PDF, withNewFont))
                    .describedAs("same family, different bytes: the PDF can differ, so the "
                            + "fingerprint must")
                    .isNotEqualTo(RenderFingerprint.of(RenderFormat.PDF, original));
        }

        @Test
        @DisplayName("the fingerprint does not depend on the order fonts were added in")
        void fingerprintIsOrderIndependent() {
            RenderMetadata oneWay = RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2")
                    .font("Pretendard", "a").font("Noto Sans CJK KR", "b").build();
            RenderMetadata otherWay = RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2")
                    .font("Noto Sans CJK KR", "b").font("Pretendard", "a").build();

            assertThat(RenderFingerprint.of(RenderFormat.PDF, oneWay))
                    .describedAs("otherwise a re-render 'differs' for a reason that is not a "
                            + "difference, and archived-is-authoritative fires on noise")
                    .isEqualTo(RenderFingerprint.of(RenderFormat.PDF, otherWay));
        }

        @Test
        @DisplayName("the mdv build time is part of the configuration, because now() is config")
        void mdvBuildTimeIsPartOfTheConfiguration() {
            RenderMetadata earlier = RenderMetadata.builder()
                    .rendererVersion("mdv 0.0.0").extra("mdv.buildTime", "2026-08-18T09:00:00.000")
                    .build();
            RenderMetadata later = RenderMetadata.builder()
                    .rendererVersion("mdv 0.0.0").extra("mdv.buildTime", "2026-08-19T09:00:00.000")
                    .build();

            assertThat(RenderFingerprint.of(RenderFormat.PDF, earlier))
                    .isNotEqualTo(RenderFingerprint.of(RenderFormat.PDF, later));
        }
    }

    @Nested
    @DisplayName("a difference can be explained")
    class Explaining {

        @Test
        @DisplayName("the archived metadata reconstructs and names what changed")
        void differencesAreNamed() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            List<String> differences =
                    renders.explainDifference(archived, metadata("LibreOffice 25.2.0.1"));

            assertThat(differences).anySatisfy(difference ->
                    assertThat(difference).contains("renderer changed")
                            .contains("24.2.7.2").contains("25.2.0.1"));
        }

        @Test
        @DisplayName("a removed font is named as the reason rather than left a mystery")
        void aRemovedFontIsNamed() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");
            RenderMetadata withoutNoto = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .template("template-1", 3)
                    .font("Pretendard", "hash-pretendard")
                    .locale("ko")
                    .build();

            assertThat(renders.explainDifference(archived, withoutNoto))
                    .anySatisfy(difference -> assertThat(difference)
                            .contains("font no longer present").contains("Noto Sans CJK KR"));
        }

        @Test
        @DisplayName("identical inputs explain nothing, which is the uncomfortable answer")
        void identicalInputsExplainNothing() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            assertThat(renders.explainDifference(archived, metadata("LibreOffice 24.2.7.2")))
                    .describedAs("if the bytes still differ, the renderer is less deterministic "
                            + "than its version number claims")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the record describes the artefact it was stored with")
    class Consistency {

        @Test
        @DisplayName("the output hash recorded is the hash of the bytes stored")
        void theHashIsTheBytes() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            assertThat(archived.outputSha256()).isEqualTo(LocalFileBlobStore.sha256Hex(PDF));
            assertThat(archived.outputBlobSha256()).isEqualTo(archived.outputSha256());
        }

        @Test
        @DisplayName("metadata claiming a different output than the bytes is refused")
        void aMismatchedClaimIsRefused() {
            RenderMetadata lying = RenderMetadata.builder()
                    .rendererVersion("LibreOffice 24.2.7.2")
                    .outputSha256("0000000000000000000000000000000000000000000000000000000000000000")
                    .build();

            assertThatThrownBy(() -> renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF, lying,
                    PDF, "application/pdf"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("describes a different artefact");
        }

        @Test
        @DisplayName("the pinned template version is recorded with the render")
        void theTemplateVersionIsRecorded() {
            DocumentRenderEntity archived = renders.archive(COMPANY, DOCUMENT, 1, RenderFormat.PDF,
                    metadata("LibreOffice 24.2.7.2"), PDF, "application/pdf");

            assertThat(archived.templateId()).isEqualTo("template-1");
            assertThat(archived.templateVersionNo()).isEqualTo(3);
            assertThat(archived.locale()).isEqualTo("ko");
            assertThat(archived.extra()).contains("mdv.buildTime=2026-08-18T09:00:00.000");
        }
    }
}
