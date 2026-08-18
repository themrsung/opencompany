package com.coreintra.documents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.documents.entity.SignatureImageEntity;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.signature.SignatureCompositor;
import com.coreintra.documents.signature.SignatureImpression;
import com.coreintra.documents.signature.SignatureService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.InMemoryBlobStore;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A 도장 is a forgery kit, so the code is arranged so that serving one is not expressible.
 *
 * <p>Brief 6.3 requires signature images to be composited server-side at render only and
 * never served to the browser as a reusable asset. A rule in a document does not survive a
 * refactor; the absence of a method does. These tests hold that absence in place.
 */
class SignatureBoundaryTest {

    private static final String COMPANY = "company-1";
    private static final String EMPLOYEE = "employee-1";
    private static final String ACCOUNT = "account-1";
    private static final BusinessInstant NOW =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0);
    private static final byte[] SEAL = "PNG-seal-bytes".getBytes(StandardCharsets.UTF_8);

    private static JavaClasses production;

    private Fakes.Signatures signatureRows;
    private Fakes.SignatureUses useRows;
    private SignatureService signatures;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.coreintra.documents");
    }

    @BeforeEach
    void setUp() {
        signatureRows = new Fakes.Signatures();
        useRows = new Fakes.SignatureUses();
        signatures = new SignatureService(signatureRows, useRows,
                new BlobService(new InMemoryBlobStore(), new Fakes.Blobs()));
    }

    private SignatureImageEntity enrolled() {
        return signatures.enrol(COMPANY, EMPLOYEE, SignatureImageEntity.Kind.SEAL, SEAL,
                "image/png", ACCOUNT, NOW);
    }

    @Nested
    @DisplayName("no public path returns the bytes")
    class NoPublicPath {

        /**
         * ACCEPTANCE: signature bytes cannot be obtained through any public path that a
         * controller could call.
         *
         * <p>Checked against the compiled package rather than a list of class names, so it
         * still holds for the class somebody adds next year.
         */
        @Test
        @DisplayName("nothing public in the signature package returns bytes or a stream")
        void thePackageHasNoByteReturningApi() {
            ArchRule rule = methods()
                    .that().arePublic()
                    .and().areDeclaredInClassesThat()
                            .resideInAPackage("com.coreintra.documents.signature..")
                    .should().notHaveRawReturnType(byte[].class)
                    .andShould().notHaveRawReturnType(InputStream.class)
                    .because("a 도장 is composited server-side at render only and is never served "
                            + "to the browser as a reusable asset. The guarantee is that there is "
                            + "no expression a controller could write that yields the bytes.");
            rule.check(production);
        }

        @Test
        @DisplayName("the impression itself exposes a length and an id, and nothing else")
        void theImpressionIsOpaque() {
            for (Method method : SignatureImpression.class.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                assertThat(method.getReturnType())
                        .describedAs("SignatureImpression#" + method.getName()
                                + " must not hand out content")
                        .isNotEqualTo(byte[].class)
                        .isNotEqualTo(InputStream.class);
            }
        }

        @Test
        @DisplayName("the signature row has no accessor for its content hash")
        void theEntityDoesNotLeakTheHashEither() {
            for (Method method : SignatureImageEntity.class.getMethods()) {
                assertThat(method.getName().toLowerCase(java.util.Locale.ROOT))
                        .describedAs("a getter here is one line away from an <img src> pointing "
                                + "at somebody's seal")
                        .doesNotContain("sha256")
                        .doesNotContain("blob");
            }
        }

        @Test
        @DisplayName("the one door leads to a file in a render workspace, not to a return value")
        void theCompositorWritesToDisk(@TempDir Path workspace) {
            enrolled();
            SignatureImpression impression = signatures.impressionFor(EMPLOYEE,
                    SignatureImageEntity.Kind.SEAL, "doc-1", Integer.valueOf(1), ACCOUNT,
                    "결재란 compositing").get();

            String fileName = new SignatureCompositor().materialiseInto(workspace, impression);

            assertThat(workspace.resolve(fileName)).exists();
            assertThat(fileName).endsWith(".png");
            assertThat(impression.byteLength()).isEqualTo(SEAL.length);
        }

        @Test
        @DisplayName("materialising into a workspace that does not exist is refused")
        void theWorkspaceIsTheCallersToCreate(@TempDir Path workspace) {
            enrolled();
            SignatureImpression impression = signatures.impressionFor(EMPLOYEE,
                    SignatureImageEntity.Kind.SEAL, "doc-1", Integer.valueOf(1), ACCOUNT,
                    "결재란 compositing").get();

            assertThatThrownBy(() -> new SignatureCompositor()
                    .materialiseInto(workspace.resolve("absent"), impression))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must exist");
        }
    }

    @Nested
    @DisplayName("enrolment, revocation and the use log")
    class Lifecycle {

        @Test
        @DisplayName("enrolling a replacement revokes the previous seal rather than overwriting it")
        void replacementRevokesRatherThanOverwrites() {
            SignatureImageEntity first = enrolled();

            signatures.enrol(COMPANY, EMPLOYEE, SignatureImageEntity.Kind.SEAL,
                    "PNG-new-seal".getBytes(StandardCharsets.UTF_8), "image/png", ACCOUNT, NOW);

            SignatureImageEntity previous = signatureRows.findById(first.id()).get();
            assertThat(previous.revokedAt())
                    .describedAs("a document rendered last year carried this one; the trail must "
                            + "still be able to say so")
                    .isNotNull();
            assertThat(previous.revokedReason()).contains("replaced");
            assertThat(signatures.liveSealsOf(COMPANY)).hasSize(1);
        }

        @Test
        @DisplayName("a revoked seal yields no impression")
        void revokedSealsDoNotRender() {
            SignatureImageEntity seal = enrolled();
            signatures.revoke(seal.id(), ACCOUNT, "퇴사 (employee left)", OffsetDateTime.now());

            Optional<SignatureImpression> impression = signatures.impressionFor(EMPLOYEE,
                    SignatureImageEntity.Kind.SEAL, "doc-1", Integer.valueOf(1), ACCOUNT, "결재란");

            assertThat(impression).isEmpty();
        }

        @Test
        @DisplayName("a revocation without a reason is refused")
        void revocationsCarryTheirReason() {
            SignatureImageEntity seal = enrolled();

            assertThatThrownBy(
                    () -> signatures.revoke(seal.id(), ACCOUNT, "  ", OffsetDateTime.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("every impression is recorded against the document that caused it")
        void everyImpressionIsRecorded() {
            SignatureImageEntity seal = enrolled();

            signatures.impressionFor(EMPLOYEE, SignatureImageEntity.Kind.SEAL, "doc-1",
                    Integer.valueOf(3), "approver-9", "결재란 compositing");

            assertThat(signatures.usesOf(seal.id())).hasSize(1);
            assertThat(signatures.usesOf(seal.id()).get(0).documentId()).isEqualTo("doc-1");
            assertThat(signatures.usesOf(seal.id()).get(0).versionNo()).isEqualTo(3);
            assertThat(signatures.usesOf(seal.id()).get(0).requestedByAccountId())
                    .describedAs("who caused the render, which is not always whose seal it is")
                    .isEqualTo("approver-9");
        }

        @Test
        @DisplayName("an impression with no stated purpose is refused")
        void impressionsStateTheirPurpose() {
            enrolled();

            assertThatThrownBy(() -> signatures.impressionFor(EMPLOYEE,
                    SignatureImageEntity.Kind.SEAL, "doc-1", Integer.valueOf(1), ACCOUNT, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("what the seal is being used for");
        }

        @Test
        @DisplayName("an employee with no seal is not an error: the 결재란 renders the name alone")
        void noSealIsNormal() {
            assertThat(signatures.impressionFor("employee-with-no-seal",
                    SignatureImageEntity.Kind.SEAL, "doc-1", Integer.valueOf(1), ACCOUNT, "결재란"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an empty image is refused, because a blank 결재란 looks signed")
        void emptySealsAreRefused() {
            assertThatThrownBy(() -> signatures.enrol(COMPANY, EMPLOYEE,
                    SignatureImageEntity.Kind.SEAL, new byte[0], "image/png", ACCOUNT, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("looks signed");
        }
    }
}
