package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.SeededTemplateInstallEntity;
import com.coreintra.documents.entity.SeededTemplateInstallId;
import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatRegistry;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.internal.adapter.DocxAdapter;
import com.coreintra.documents.internal.adapter.HwpLegacyAdapter;
import com.coreintra.documents.internal.adapter.HwpxAdapter;
import com.coreintra.documents.internal.adapter.MdvAdapter;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.DocxWriter;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.repository.SeededTemplateInstallRepository;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import com.coreintra.documents.seed.SeededTemplate;
import com.coreintra.documents.seed.SeededTemplateCatalogue;
import com.coreintra.documents.seed.SeededTemplateInstaller;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.TemplateService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.InMemoryBlobStore;
import com.coreintra.documents.support.InMemoryRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seven factory templates (§6.8).
 *
 * <p>The exports are asserted on the <b>artefact</b>: the bytes are read back
 * and the Korean has to still be in them. A test that only checked no exception
 * was thrown would pass just as happily against a writer that emitted an empty
 * document, which is the failure mode that actually happens.
 */
class SeededTemplateTest {

    private static final BusinessInstant AT =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 9 * 3600);

    private static final String COMPANY = "company-1";

    @Nested
    @DisplayName("The catalogue")
    class Catalogue {

        @Test
        @DisplayName("ships exactly the seven templates §6.8 names")
        void shipsSeven() {
            List<SeededTemplate> all = SeededTemplateCatalogue.all();
            List<String> names = new ArrayList<String>();
            for (SeededTemplate template : all) {
                names.add(template.nameKo());
            }
            assertThat(all).hasSize(7);
            assertThat(names).containsExactlyInAnyOrder(
                    "휴가신청서", "지출결의서 / 지출내역서", "회의록", "일반안건",
                    "이사회안건", "주주총회안건", "사직서");
        }

        @Test
        @DisplayName("gives every template a Korean and an English body")
        void bothLanguages() {
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                assertThat(template.body(SeededTemplate.LOCALE_KO))
                        .as("%s has a Korean body", template.code()).isNotNull();
                assertThat(template.body(SeededTemplate.LOCALE_EN))
                        .as("%s has an English body", template.code()).isNotNull();
            }
        }

        @Test
        @DisplayName("declares every field its bodies contain, and contains every field it declares")
        void schemaMatchesBothBodies() {
            // The same cross-validation TemplateService runs at publish time. If
            // it fails here it fails on first boot, which is a worse place to
            // find out.
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                for (String locale : new String[] {
                    SeededTemplate.LOCALE_KO, SeededTemplate.LOCALE_EN}) {
                    byte[] docx = new DocxWriter().write(template.body(locale));
                    DocumentFieldSchema.ValidationResult result = template.schema()
                            .validateAgainst(OoxmlPackage.read(docx).documentPart());
                    assertThat(result.isValid())
                            .as("%s (%s): %s", template.code(), locale, result.describe())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("binds a 결재란 on every form that gets signed")
        void formsCarryAnApprovalBlock() {
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                if (SeededTemplateCatalogue.MEETING_MINUTES.equals(template.code())) {
                    // Minutes record what was decided; they are not themselves a
                    // request for a decision, so they carry no 결재란.
                    continue;
                }
                byte[] docx = new DocxWriter().write(
                        template.body(SeededTemplate.LOCALE_KO));
                DocumentFieldSchema.ValidationResult result = template.schema()
                        .validateAgainst(OoxmlPackage.read(docx).documentPart());
                assertThat(result.hasApprovalBlock())
                        .as("%s carries a 결재란", template.code()).isTrue();
            }
        }

        @Test
        @DisplayName("writes Korean copy in 합쇼체, not in the plain style")
        void koreanIsPolite() {
            // A 결재 document written in 해라체 reads as a machine talking to a
            // subordinate. Every Korean sentence in the seeded set ends politely.
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                for (String line : textOf(template.body(SeededTemplate.LOCALE_KO)).split("\n")) {
                    // Not a bare "." — "0.5일" is one number, not two sentences.
                    for (String sentence : line.split("(?<=\\.)(?![0-9])")) {
                        String trimmed = sentence.trim();
                        if (trimmed.isEmpty() || !containsHangul(trimmed)
                                || !trimmed.endsWith(".")) {
                            continue;
                        }
                        String ending = trimmed.substring(0, trimmed.length() - 1);
                        assertThat(ending)
                                .as("\"%s\" in %s should end in 합쇼체", trimmed, template.code())
                                // 합쇼체 is -ㅂ니다/-습니다 and the -십시오 imperative. Enumerating
                                // stems instead ("됩니다", "합니다"…) just misses the next
                                // verb somebody writes.
                                .matches("(?s).*(니다|십시오)$");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Every seeded template, in every language")
    class Exports {

        @Test
        @DisplayName("exports to every writable format with the Korean text intact")
        void exportsToEveryFormat() {
            FormatRegistry registry = new FormatRegistry()
                    .register(new DocxAdapter())
                    .register(new HwpxAdapter())
                    .register(new MdvAdapter())
                    .register(new HwpLegacyAdapter());

            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                for (Map.Entry<String, InternalDoc> body : template.bodies().entrySet()) {
                    boolean korean = body.getKey().startsWith("ko");
                    String expected = korean ? template.nameKo() : template.nameEn();
                    if (expected.contains("/")) {
                        expected = expected.substring(0, expected.indexOf('/')).trim();
                    }

                    for (DocumentAdapter adapter : registry.adapters()) {
                        if (!adapter.capabilities().canWrite()) {
                            continue;
                        }
                        byte[] exported = adapter.write(body.getValue());
                        assertThat(exported)
                                .as("%s/%s as %s produced bytes", template.code(), body.getKey(),
                                        adapter.capabilities().formatId())
                                .isNotEmpty();

                        // Assert on the artefact: read it back and look for the
                        // title. A writer that emitted an empty document would
                        // pass a "did not throw" test and fail this one.
                        String readBack = textOf(adapter.read(exported));
                        assertThat(readBack)
                                .as("%s/%s as %s still contains \"%s\"", template.code(),
                                        body.getKey(), adapter.capabilities().formatId(), expected)
                                .contains(expected);
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps every field binding through a DOCX export")
        void keepsFieldBindings() {
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                byte[] docx = new DocxWriter().write(template.body(SeededTemplate.LOCALE_KO));
                Map<String, String> controls =
                        ContentControls.readValues(OoxmlPackage.read(docx).documentPart());
                for (FieldDefinition field : template.schema().fields()) {
                    assertThat(controls)
                            .as("%s keeps a control for %s", template.code(), field.tag())
                            .containsKey(field.tag());
                }
            }
        }

        @Test
        @DisplayName("keeps every field binding through an HWPX export, which has no controls")
        void keepsFieldBindingsThroughHwpx() {
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                byte[] hwpx = new HwpxAdapter().write(template.body(SeededTemplate.LOCALE_KO));
                InternalDoc back = new HwpxAdapter().read(hwpx);
                for (FieldDefinition field : template.schema().fields()) {
                    assertThat(back.fieldValues())
                            .as("%s keeps 누름틀 %s", template.code(), field.tag())
                            .containsKey(field.tag());
                }
            }
        }

        @Test
        @DisplayName("produces the same bytes every time, so installing twice stores one copy")
        void isDeterministic() {
            for (SeededTemplate template : SeededTemplateCatalogue.all()) {
                InternalDoc body = template.body(SeededTemplate.LOCALE_KO);
                assertThat(new DocxWriter().write(body))
                        .as("%s is byte-stable", template.code())
                        .isEqualTo(new DocxWriter().write(body));
            }
        }
    }

    @Nested
    @DisplayName("지출결의서")
    class Expense {

        private SeededTemplate template() {
            return find(SeededTemplateCatalogue.EXPENSE_APPROVAL);
        }

        @Test
        @DisplayName("supplies net, VAT and gross as three separate fields")
        void netVatGrossAreSeparate() {
            List<String> tags = tagsOf(template());
            assertThat(tags).contains("totalNetAmount", "totalVatAmount", "totalGrossAmount");
            // Per line as well as in total: rounding is per line, so a form that
            // only totalled would not match the 세금계산서 it is copied from.
            assertThat(tags).contains("line1NetAmount", "line1VatAmount", "line1GrossAmount");
            for (FieldDefinition field : template().schema().fields()) {
                if (field.tag().endsWith("NetAmount") || field.tag().endsWith("VatAmount")
                        || field.tag().endsWith("GrossAmount")) {
                    assertThat(field.type())
                            .as("%s is money", field.tag()).isEqualTo(FieldType.MONEY);
                }
            }
        }

        @Test
        @DisplayName("says in the document that VAT is entered rather than calculated")
        void saysVatIsNotDerived() {
            assertThat(textOf(template().body(SeededTemplate.LOCALE_KO)))
                    .contains("자동으로 계산하지 않습니다");
            assertThat(textOf(template().body(SeededTemplate.LOCALE_EN)))
                    .contains("Neither is calculated from the other");
        }

        @Test
        @DisplayName("carries a per-line VAT rate")
        void vatRateIsPerLine() {
            assertThat(tagsOf(template())).contains("line1VatRate", "line2VatRate", "line3VatRate");
        }

        @Test
        @DisplayName("distinguishes zero-rated and exempt from a rate of zero")
        void zeroRatedIsNotExemptIsNotZero() {
            String korean = textOf(template().body(SeededTemplate.LOCALE_KO));
            assertThat(korean).contains("영세율").contains("면세").contains("과세");
            assertThat(korean)
                    .as("the difference is stated, not just the words listed")
                    .contains("세율 0%가 아니라");

            String english = textOf(template().body(SeededTemplate.LOCALE_EN));
            assertThat(english).contains("Zero-rated").contains("Exempt");
            assertThat(english).contains("Not a 0% rate");
            assertThat(tagsOf(template())).contains("line1TaxTreatment");
        }
    }

    @Nested
    @DisplayName("이사회안건")
    class BoardAgenda {

        private SeededTemplate template() {
            return find(SeededTemplateCatalogue.BOARD_AGENDA);
        }

        @Test
        @DisplayName("records a vote per director, not one vote for the board")
        void votePerDirector() {
            List<String> tags = tagsOf(template());
            for (int seat = 1; seat <= 5; seat++) {
                assertThat(tags).contains("director" + seat + "Name", "director" + seat + "Vote");
            }
            assertThat(textOf(template().body(SeededTemplate.LOCALE_KO)))
                    .contains("찬성").contains("반대").contains("기권");
        }

        @Test
        @DisplayName("flags a 특별이해관계자 as recused, separately from abstaining")
        void recusalIsItsOwnFlag() {
            List<String> tags = tagsOf(template());
            for (int seat = 1; seat <= 5; seat++) {
                assertThat(tags).contains("director" + seat + "Recused");
            }
            // A director who may not vote has not abstained, and a minute that
            // says otherwise looks lawful and is not.
            assertThat(textOf(template().body(SeededTemplate.LOCALE_KO)))
                    .contains("특별이해관계")
                    .contains("기권이 아니라");
        }

        @Test
        @DisplayName("records the quorum it was decided under")
        void recordsQuorum() {
            assertThat(tagsOf(template()))
                    .contains("totalDirectors", "directorsPresent", "quorumMet");
        }

        @Test
        @DisplayName("demonstrates mdv on first run, with a real chart")
        void carriesAnMdvCompanion() {
            InternalDoc mdv = template().body(SeededTemplate.LOCALE_KO_MDV);
            assertThat(mdv).isNotNull();
            String source = new String(new MdvAdapter().write(mdv), StandardCharsets.UTF_8);
            assertThat(source).contains("```mdv pie").contains("```mdv line");
            assertThat(source).contains("찬성");
        }
    }

    @Nested
    @DisplayName("주주총회안건")
    class ShareholderAgenda {

        private SeededTemplate template() {
            return find(SeededTemplateCatalogue.SHAREHOLDER_AGENDA);
        }

        @Test
        @DisplayName("distinguishes an ordinary resolution from a special one")
        void ordinaryVersusSpecial() {
            assertThat(tagsOf(template())).contains("resolutionClass");
            String korean = textOf(template().body(SeededTemplate.LOCALE_KO));
            assertThat(korean).contains("보통결의").contains("특별결의");
            assertThat(korean).contains("과반수").contains("3분의 2");
        }

        @Test
        @DisplayName("weights the vote by share count, and says so")
        void votesAreWeightedByShares() {
            assertThat(tagsOf(template()))
                    .contains("sharesFor", "sharesAgainst", "sharesAbstain",
                            "totalSharesIssued", "sharesPresent", "votingShares");
            assertThat(textOf(template().body(SeededTemplate.LOCALE_KO)))
                    .contains("주식 수입니다. 주주 수가 아닙니다");
        }

        @Test
        @DisplayName("records the quorum thresholds as editable copy, not as a code path")
        void thresholdsAreCopy() {
            // Statute changes and articles override it. Putting the numbers in
            // the body means a client can correct them without a release.
            assertThat(textOf(template().body(SeededTemplate.LOCALE_KO)))
                    .contains("정관이 우선합니다");
        }
    }

    @Nested
    @DisplayName("휴가신청서 and 사직서, which drive something")
    class Couplings {

        @Test
        @DisplayName("휴가신청서 carries what the leave ledger needs")
        void leaveLedgerFields() {
            List<String> tags = tagsOf(find(SeededTemplateCatalogue.LEAVE_REQUEST));
            assertThat(tags).contains("leaveType", "leaveStartDate", "leaveEndDate", "leaveDays");
            assertThat(textOf(find(SeededTemplateCatalogue.LEAVE_REQUEST)
                    .body(SeededTemplate.LOCALE_KO))).contains("연차 원장");
        }

        @Test
        @DisplayName("사직서 carries the termination date that deactivates the account")
        void offboardingFields() {
            List<String> tags = tagsOf(find(SeededTemplateCatalogue.RESIGNATION));
            assertThat(tags).contains("resignationDate", "lastWorkingDate", "handoverTo");
            assertThat(textOf(find(SeededTemplateCatalogue.RESIGNATION)
                    .body(SeededTemplate.LOCALE_KO))).contains("비활성화");
        }
    }

    @Nested
    @DisplayName("Installing the factory set")
    class Installing {

        private SeededTemplateInstaller installer;
        private Fakes.Templates templates;
        private Fakes.TemplateVersions versions;
        private Fakes.TemplateBodies bodies;
        private SeededTemplateInstallRepository installs;

        @BeforeEach
        void setUp() {
            templates = new Fakes.Templates();
            versions = new Fakes.TemplateVersions();
            bodies = new Fakes.TemplateBodies();
            Fakes.Blobs blobs = new Fakes.Blobs();
            InMemoryBlobStore store = new InMemoryBlobStore();
            installs = new SeedInstalls();
            TemplateService service = new TemplateService(templates, versions, bodies,
                    new BlobService(store, blobs));
            installer = new SeededTemplateInstaller(service, templates, installs,
                    new com.coreintra.documents.support.InMemoryBinaryStore());
        }

        @Test
        @DisplayName("installs all seven on first boot")
        void installsOnFirstBoot() {
            assertThat(installer.install(COMPANY, AT)).hasSize(7);
            assertThat(templates.findAll()).hasSize(7);
            for (DocumentTemplateEntity template : templates.findAll()) {
                assertThat(template.isBuiltIn())
                        .as("%s is restorable to factory state", template.code()).isTrue();
                assertThat(template.currentVersionNo()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("does nothing at all on the second boot")
        void isIdempotent() {
            installer.install(COMPANY, AT);
            int versionsAfterFirst = versions.findAll().size();
            int blobsAfterFirst = bodies.findAll().size();

            // A version per restart would be visible to the client and would
            // give submitted documents a version to be bound to that nobody
            // asked for.
            assertThat(installer.install(COMPANY, AT)).isEmpty();
            assertThat(versions.findAll()).hasSize(versionsAfterFirst);
            assertThat(bodies.findAll()).hasSize(blobsAfterFirst);
        }

        @Test
        @DisplayName("publishes both bodies of every template, and the mdv companions")
        void publishesEveryBody() {
            installer.install(COMPANY, AT);

            for (DocumentTemplateEntity template : templates.findAll()) {
                List<DocumentTemplateBodyEntity> published =
                        bodies.findByTemplateIdAndVersionNo(template.id(), Integer.valueOf(1));
                List<String> locales = new ArrayList<String>();
                for (DocumentTemplateBodyEntity body : published) {
                    locales.add(body.locale());
                }
                assertThat(locales).contains("ko", "en");

                boolean expectsMdv =
                        SeededTemplateCatalogue.BOARD_AGENDA.equals(template.code())
                        || SeededTemplateCatalogue.MEETING_MINUTES.equals(template.code());
                if (expectsMdv) {
                    assertThat(locales).contains("ko-x-mdv", "en-x-mdv");
                    for (DocumentTemplateBodyEntity body : published) {
                        if (body.locale().endsWith("-x-mdv")) {
                            assertThat(body.format()).isEqualTo(DocumentFormat.MDV);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("leaves a template the client already owns under our code alone")
        void doesNotOverwriteAClientTemplate() {
            // The client got there first. Replacing their document with a
            // generated one is not an upgrade, it is data loss.
            DocumentTemplateEntity theirs = new DocumentTemplateEntity(
                    "their-id", COMPANY, SeededTemplateCatalogue.LEAVE_REQUEST,
                    "leave-request", "우리 회사 휴가신청서", "someone");
            templates.save(theirs);

            List<String> installed = installer.install(COMPANY, AT);

            assertThat(installed).doesNotContain(SeededTemplateCatalogue.LEAVE_REQUEST);
            assertThat(templates.findByCompanyIdAndCode(COMPANY,
                    SeededTemplateCatalogue.LEAVE_REQUEST).get().nameKo())
                    .isEqualTo("우리 회사 휴가신청서");
        }

        @Test
        @DisplayName("records the revision, so a later catalogue can be recognised as newer")
        void recordsTheRevision() {
            installer.install(COMPANY, AT);
            Optional<SeededTemplateInstallEntity> record = installs.findByCompanyIdAndCode(
                    COMPANY, SeededTemplateCatalogue.RESIGNATION);
            assertThat(record).isPresent();
            assertThat(record.get().catalogueRevision())
                    .isEqualTo(SeededTemplateCatalogue.REVISION);
            assertThat(record.get().installedVersionNo()).isEqualTo(1);
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static SeededTemplate find(String code) {
        for (SeededTemplate template : SeededTemplateCatalogue.all()) {
            if (template.code().equals(code)) {
                return template;
            }
        }
        throw new AssertionError("no seeded template with code " + code);
    }

    private static List<String> tagsOf(SeededTemplate template) {
        List<String> tags = new ArrayList<String>();
        for (FieldDefinition field : template.schema().fields()) {
            tags.add(field.tag());
        }
        return tags;
    }

    private static boolean containsHangul(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character >= 0xAC00 && character <= 0xD7A3) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(InternalDoc document) {
        StringBuilder text = new StringBuilder();
        appendText(document.blocks(), text);
        return text.toString();
    }

    private static void appendText(List<InternalDoc.Block> blocks, StringBuilder text) {
        for (InternalDoc.Block block : blocks) {
            if (block instanceof InternalDoc.Paragraph) {
                text.append(((InternalDoc.Paragraph) block).text()).append('\n');
            } else if (block instanceof InternalDoc.FieldBlock) {
                text.append(((InternalDoc.FieldBlock) block).value()).append('\n');
            } else if (block instanceof InternalDoc.ControlBlock) {
                appendText(((InternalDoc.ControlBlock) block).blocks(), text);
            } else if (block instanceof InternalDoc.Table) {
                for (List<List<InternalDoc.Block>> row : ((InternalDoc.Table) block).rows()) {
                    for (List<InternalDoc.Block> cell : row) {
                        appendText(cell, text);
                    }
                }
            } else if (block instanceof InternalDoc.OpaqueBlock) {
                text.append(new String(((InternalDoc.OpaqueBlock) block).originalBytes(),
                        StandardCharsets.UTF_8)).append('\n');
            }
        }
    }

    /** In-memory {@link SeededTemplateInstallRepository}, matching the other fakes. */
    private static final class SeedInstalls
            extends InMemoryRepository<SeededTemplateInstallEntity, SeededTemplateInstallId>
            implements SeededTemplateInstallRepository {

        SeedInstalls() {
            super(entity -> new SeededTemplateInstallId(entity.companyId(), entity.code()));
        }

        @Override
        public List<SeededTemplateInstallEntity> findByCompanyId(String companyId) {
            List<SeededTemplateInstallEntity> found =
                    new ArrayList<SeededTemplateInstallEntity>();
            for (SeededTemplateInstallEntity entity : findAll()) {
                if (entity.companyId().equals(companyId)) {
                    found.add(entity);
                }
            }
            return found;
        }

        @Override
        public Optional<SeededTemplateInstallEntity> findByCompanyIdAndCode(String companyId,
                String code) {
            for (SeededTemplateInstallEntity entity : findAll()) {
                if (entity.companyId().equals(companyId) && entity.code().equals(code)) {
                    return Optional.of(entity);
                }
            }
            return Optional.empty();
        }
    }
}
