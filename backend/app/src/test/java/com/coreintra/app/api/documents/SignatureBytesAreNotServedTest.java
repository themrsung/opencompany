package com.coreintra.app.api.documents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 도장 and 서명 images are never served to the browser (§6.3).
 *
 * <h2>Why this is a test and not a convention</h2>
 *
 * <p>A seal image is a reusable credential. Once it has been served as a file,
 * anybody who has ever opened a document carrying it can paste it onto anything,
 * and no audit trail records that. So it is composited server-side into the
 * render and never leaves as an asset — and the failure mode this guards against
 * is not malice but helpfulness: somebody adding "just a thumbnail endpoint" for
 * the enrolment screen.
 *
 * <h2>What is asserted, and why it is not a naming check</h2>
 *
 * <p>The service is built so the bytes are hard to reach: {@code SignatureImpression}
 * has no public accessor for its content, and the only thing that can read it is
 * {@code SignatureCompositor}, which writes into the renderer's workspace. So the
 * assertion is structural — nothing in the application layer may depend on either
 * type — which makes an endpoint that returns those bytes impossible to write
 * rather than merely against the rules.
 *
 * <p>Enrolment and revocation are deliberately left reachable: those are
 * legitimate operations on the images, and this must not be the test that stops
 * somebody building the enrolment screen.
 */
@SpringBootTest
@ActiveProfiles("test")
class SignatureBytesAreNotServedTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    /**
     * Named explicitly: the actuator contributes a second
     * {@link RequestMappingHandlerMapping} for its own endpoints, and the one
     * this test is about is the application's.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("no class in the application layer can even obtain a signature image's bytes")
    void theBytesAreUnreachableFromTheApiLayer() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.coreintra.app");

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.coreintra.app..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "com.coreintra.documents.signature.SignatureImpression")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.coreintra.documents.signature.SignatureCompositor")
                .because("a 도장 is a reusable credential. It is composited into the render "
                        + "server-side and never served as an asset (§6.3); an API class that "
                        + "could hold the impression is one refactor away from returning it.");

        rule.check(production);
    }

    @Test
    @DisplayName("the impression still refuses to hand out its bytes")
    void theImpressionHasNoPublicAccessor() throws Exception {
        Class<?> impression =
                Class.forName("com.coreintra.documents.signature.SignatureImpression");

        List<String> leaks = new ArrayList<String>();
        Method[] methods = impression.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Class<?> returned = methods[i].getReturnType();
            if (byte[].class.equals(returned) || Resource.class.isAssignableFrom(returned)
                    || InputStreamSource.class.isAssignableFrom(returned)) {
                leaks.add(methods[i].getName());
            }
        }

        assertThat(leaks)
                .as("the whole design rests on there being no public way to read the image; "
                        + "opening one up would make the endpoint rule unenforceable")
                .isEmpty();
    }

    @Test
    @DisplayName("no mapped endpoint returns bytes from anything to do with signatures")
    void noEndpointServesThem() {
        List<String> offenders = new ArrayList<String>();
        Map<RequestMappingInfo, HandlerMethod> handlers = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
            String path = String.valueOf(entry.getKey());
            String declaring = entry.getValue().getBeanType().getName();
            String signature = entry.getValue().getMethod().toGenericString();

            boolean aboutSignatures = mentionsSignature(path) || mentionsSignature(declaring)
                    || mentionsSignature(signature);
            if (aboutSignatures && couldCarryBytes(entry.getValue())) {
                offenders.add(declaring + "#" + entry.getValue().getMethod().getName()
                        + " -> " + path);
            }
        }

        assertThat(offenders)
                .as("§6.3: never served to the browser as a reusable asset, in any format, to "
                        + "any caller")
                .isEmpty();
    }

    /**
     * Deliberately narrow.
     *
     * <p>Only a declared byte-bearing return type counts. Flagging every
     * {@code ResponseEntity} on a signature path would also fail the enrolment
     * screen's metadata endpoints, which are legitimate — and a test that fails
     * correct work gets deleted rather than fixed. The structural rule above is
     * what makes the byte case impossible; this is the second line, for a handler
     * that declares it returns a file.
     */
    private static boolean couldCarryBytes(HandlerMethod handler) {
        Class<?> returned = handler.getMethod().getReturnType();
        return byte[].class.equals(returned) || Resource.class.isAssignableFrom(returned)
                || InputStreamSource.class.isAssignableFrom(returned);
    }

    private static boolean mentionsSignature(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("signature") || lower.contains("seal")
                || lower.contains("stamp") || text.contains("도장") || text.contains("서명");
    }
}
