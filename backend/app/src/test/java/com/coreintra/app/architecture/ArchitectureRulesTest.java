package com.coreintra.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules that must hold whatever anyone is in a hurry to ship.
 *
 * <p>Each of these is a decision recorded in an ADR that would otherwise decay
 * quietly: a single {@code double} in a money path, one controller reaching
 * past the permission evaluator, one {@code jakarta.*} import that breaks the
 * Java 8 target. Review catches these until it does not; the build catches them
 * every time.
 */
class ArchitectureRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.coreintra");
    }

    @Nested
    @DisplayName("Java 8 baseline (ADR 0001)")
    class JavaEightBaseline {

        @Test
        @DisplayName("nothing imports jakarta.* — Boot 2.7 is the javax.* line")
        void noJakartaImports() {
            ArchRule rule = noClasses()
                    .should().dependOnClassesThat().resideInAPackage("jakarta..")
                    .because("Spring Boot 2.7 and Hibernate 5.6 are the javax.* line. A jakarta.* "
                            + "import compiles against a mis-resolved dependency and fails at "
                            + "runtime on the client's JRE. See ADR 0001.");
            rule.check(production);
        }

        @Test
        @DisplayName("only platform-compat knows about the Java version")
        void versionShimsAreIsolated() {
            // The point of platform-compat is that bumping to 17 or 21 later
            // touches <release>, the Boot version, javax->jakarta, and this one
            // module. If shims spread, that promise is quietly broken.
            ArchRule rule = classes()
                    .that().haveSimpleName("Immutables")
                    .or().haveSimpleName("Streams")
                    .or().haveSimpleName("Optionals")
                    .or().haveSimpleName("Texts")
                    .should().resideInAPackage("com.coreintra.compat")
                    .because("every Java-version-sensitive shim lives in platform-compat (ADR 0001)");
            rule.check(production);
        }
    }

    @Nested
    @DisplayName("Money is never floating point (ADR 0004)")
    class MoneyIsExact {

        @Test
        @DisplayName("no float or double field anywhere in the accounting module")
        void noFloatingPointFields() {
            ArchRule rule = noFields()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.coreintra.accounting..")
                    .should().haveRawType(double.class)
                    .orShould().haveRawType(float.class)
                    .orShould().haveRawType(Double.class)
                    .orShould().haveRawType(Float.class)
                    .because("floating point cannot represent 0.1, so a single double in a money "
                            + "path produces entries that do not balance. BigDecimal everywhere. "
                            + "See ADR 0004.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("no method in the accounting module returns or accepts floating point")
        void noFloatingPointSignatures() {
            ArchRule returns = noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.coreintra.accounting..")
                    .should().haveRawReturnType(double.class)
                    .orShould().haveRawReturnType(float.class)
                    .orShould().haveRawReturnType(Double.class)
                    .orShould().haveRawReturnType(Float.class)
                    .because("rounding a money value into a double loses it permanently (ADR 0004)");
            returns.allowEmptyShould(true).check(production);

            ArchRule parameters = noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.coreintra.accounting..")
                    .should().haveRawParameterTypes(double.class)
                    .orShould().haveRawParameterTypes(float.class)
                    .because("an amount must arrive as BigDecimal or as an exact decimal string, "
                            + "never as a double (ADR 0004)");
            parameters.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("BigDecimal.valueOf(double) is banned in money paths")
        void noBigDecimalFromDouble() {
            // The subtle one, and the reason a runtime tripwire on Amount was
            // not enough: BigDecimal.valueOf(0.1) compiles, looks careful, and
            // carries the floating-point error straight in. It is the actual
            // way this mistake gets made.
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.coreintra.accounting..")
                    .should().callMethod(java.math.BigDecimal.class, "valueOf", double.class)
                    .because("BigDecimal.valueOf(0.1) produces 0.1000000000000000055511151231257827. "
                            + "Construct from an exact decimal string instead. See ADR 0004.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("new BigDecimal(double) is banned too")
        void noBigDecimalDoubleConstructor() {
            // Worse than valueOf: it keeps every spurious digit rather than
            // rounding to the shortest representation.
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.coreintra.accounting..")
                    .should().callConstructor(java.math.BigDecimal.class, double.class)
                    .because("new BigDecimal(0.1) is exactly 0.1000000000000000055511151231257827. "
                            + "Construct from a string. See ADR 0004.");
            rule.allowEmptyShould(true).check(production);
        }
    }

    @Nested
    @DisplayName("One permission gate, no bypass (ADR 0003)")
    class NoPermissionBypass {

        @Test
        @DisplayName("controllers never touch a repository directly")
        void controllersGoThroughServices() {
            // A controller that reaches a repository has skipped the layer where
            // the permission check lives. Row-level authorisation cannot be
            // retrofitted onto a query that already ran.
            ArchRule rule = noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("every read and write is authorised on the domain object first. A "
                            + "controller holding a repository has no way to have done that. "
                            + "See ADR 0003.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("nothing outside the permission package implements PermissionEvaluator")
        void singleEvaluatorImplementation() {
            // A second implementation is a second permission system, and the two
            // will disagree. Test doubles live in test sources, which are not
            // imported here.
            ArchRule rule = classes()
                    .that().implement("com.coreintra.core.permission.PermissionEvaluator")
                    .should().resideInAPackage("com.coreintra.core.permission")
                    .because("there is exactly one evaluator. REST, MCP, module service accounts "
                            + "and scheduled jobs all route through it, with no internal bypass "
                            + "path. See ADR 0003.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("no class is named to suggest it skips authorisation")
        void noBypassNamedClasses() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.coreintra..")
                    .should().haveSimpleNameContaining("SkipPermission")
                    .orShould().haveSimpleNameContaining("NoAuth")
                    .orShould().haveSimpleNameContaining("PermissionBypass")
                    .orShould().haveSimpleNameContaining("SystemPrincipal")
                    .because("there is no privileged internal caller. A scheduled job runs as a "
                            + "named service account whose grants can be inspected like anyone "
                            + "else's. See ADR 0003.");
            rule.allowEmptyShould(true).check(production);
        }
    }

    @Nested
    @DisplayName("Module boundaries")
    class ModuleBoundaries {

        @Test
        @DisplayName("business-time depends on nothing but platform-compat and the JDK")
        void businessTimeStaysPure() {
            // It is the foundation everything else stamps against, and it is
            // property-tested as pure domain logic. A Spring or JPA import here
            // would make that untestable in isolation.
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.coreintra.businesstime..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "javax.persistence..", "com.fasterxml..")
                    .because("business-time is pure domain logic with no framework dependency, so "
                            + "it can be property-tested in isolation and reused by any consumer");
            rule.check(production);
        }

        @Test
        @DisplayName("the client module SPI does not leak core internals")
        void moduleApiStaysStable() {
            // The SPI is a compatibility commitment to client developers. If it
            // depends on core internals, every internal refactor is a breaking
            // change for every installed client module.
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.coreintra.moduleapi..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.coreintra.core.org..",
                            "com.coreintra.accounting..",
                            "com.coreintra.approval..")
                    .because("the SPI is versioned separately and must not move when core "
                            + "internals do. See ADR 0005.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("no POI or HWP type appears outside the documents module")
        void documentEngineIsEncapsulated() {
            // The engine choice was forced by the Java 8 baseline (ADR 0007) and
            // must stay reversible. It is only reversible while no domain
            // signature mentions POI.
            ArchRule rule = noClasses()
                    .that().resideOutsideOfPackage("com.coreintra.documents..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.apache.poi..", "kr.dogfoot..")
                    .because("the DOCX and HWPX engines sit behind DocumentEngine so the choice "
                            + "stays reversible. See ADR 0007.");
            rule.allowEmptyShould(true).check(production);
        }
    }

    @Nested
    @DisplayName("Business time is not UTC (ADR 0002)")
    class BusinessTimeDiscipline {

        @Test
        @DisplayName("no entity stamps a business event with Instant or ZonedDateTime")
        void entitiesUseBusinessInstant() {
            // created_at is a UTC OffsetDateTime and is meant to be. What must
            // not happen is a business event - an approval, an attendance
            // record - being stamped with an absolute instant, because then the
            // 72-hour day has silently collapsed back into a wall clock.
            ArchRule rule = noFields()
                    .that().areDeclaredInClassesThat().areAnnotatedWith("javax.persistence.Entity")
                    .and().haveNameMatching(".*(approved|submitted|stamped|occurred|posted).*")
                    .should().haveRawType("java.time.Instant")
                    .orShould().haveRawType("java.time.ZonedDateTime")
                    .orShould().haveRawType("java.time.LocalDateTime")
                    .because("business events are stamped with BusinessInstant. An absolute "
                            + "timestamp collapses the 72-hour business day back into a wall "
                            + "clock. See ADR 0002.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("BusinessInstant is never ordered by its wire string")
        void noStringOrderingOfWireForm() {
            ArchRule rule = noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.coreintra..")
                    .should().haveNameMatching("compareWireStrings?")
                    .because("'-' sorts below every digit, so lexical comparison of wire strings "
                            + "produces a third order that is neither business nor absolute. Use "
                            + "BusinessInstantComparator. See ADR 0002.");
            rule.allowEmptyShould(true).check(production);
        }
    }

    @Nested
    @DisplayName("The wire format is a contract (ADR 0009)")
    class WireFormat {

        @Test
        @DisplayName("no response carries a BigDecimal, because JSON numbers lose money")
        void amountsCrossTheWireAsStrings() {
            // §9: amounts cross the wire as exact decimal strings, never JSON
            // numbers. A BigDecimal on a response type is one Jackson
            // configuration change away from being serialised as a number, and
            // 1400000.25 arriving as a double is a rounding error nobody sees
            // until a reconciliation fails months later. Keeping the type off
            // the boundary entirely is the only version of this rule that
            // cannot be undone by a setting.
            //
            // Scoped to getters, which is what Jackson serialises. Parsing an
            // incoming amount string into a BigDecimal is the correct inbound
            // move and must stay allowed; it is the outbound direction that
            // loses money.
            ArchRule rule = noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.coreintra.app.api..")
                    .and().arePublic()
                    .and().haveNameMatching("(get|is)[A-Z].*")
                    .should().haveRawReturnType("java.math.BigDecimal")
                    .because("§9 requires exact decimal strings on the wire. Format the value "
                            + "with toPlainString() on the DTO.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("no JPA entity is reachable from a controller signature")
        void controllersDoNotReturnEntities() {
            // An entity on a controller signature leaks the schema into the wire
            // format — every column rename becomes a breaking API change — and
            // drags lazy loading into the serialiser, where the session is
            // already closed. Both failures appear long after the commit.
            ArchRule rule = noMethods()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(
                            "org.springframework.web.bind.annotation.RestController")
                    .and().arePublic()
                    .should().haveRawReturnType(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "a JPA entity",
                                    javaClass -> javaClass.isAnnotatedWith("javax.persistence.Entity")))
                    .because("controllers return DTOs. An entity on the boundary makes the schema "
                            + "the API and serialises a detached lazy proxy.");
            rule.allowEmptyShould(true).check(production);
        }

        @Test
        @DisplayName("controllers live where the OpenAPI generator looks for them")
        void controllersAreInTheApiPackages() {
            ArchRule rule = classes()
                    .that().areAnnotatedWith(
                            "org.springframework.web.bind.annotation.RestController")
                    .should().resideInAnyPackage("com.coreintra.app.api..", "com.coreintra.app.mcp..")
                    .because("the committed OpenAPI document is the contract, and an endpoint "
                            + "somewhere else is one nobody reviews and no client can call.");
            rule.allowEmptyShould(true).check(production);
        }
    }
}
