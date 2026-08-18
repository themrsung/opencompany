package com.coreintra.app.api.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.permission.RequestScopedPrincipal;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.TemplateBody;
import com.coreintra.documents.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The document surface over HTTP, against a real database and the real
 * authentication filter.
 *
 * <p>What these cover that a service test cannot: the permission the endpoint
 * actually checks (rather than the one its javadoc claims), the cursor walk, the
 * {@code If-Match} plumbing, and the shape of the JSON a generated client will
 * be built from.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentApiTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mvc;
    @Autowired private RequestScopedPrincipal principals;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PermissionGrantRepository grants;
    @Autowired private TemplateService templates;
    @Autowired private DocumentService documents;

    private String companyId;
    private String accountId;
    private PermissionPrincipal caller;

    @BeforeEach
    void seed() {
        companyId = DocumentApiSupport.seedCompany(companies);
        accountId = DocumentApiSupport.seedAccount(accounts, "docs");
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_READ);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_WRITE);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_EXPORT);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_RETIRE);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.TEMPLATE_READ);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.TEMPLATE_WRITE);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.TEMPLATE_PUBLISH);
        caller = DocumentApiSupport.principal(accountId);
    }


    @AfterEach
    void removeWhatThisTestPutIn() {
        DocumentApiSupport.cleanUp(dataSource, companyId);
    }

    @Test
    @DisplayName("a document drafted from a template exposes its fields as typed values, "
            + "with labels, and money as an exact decimal string")
    void fieldValuesAreTyped() throws Exception {
        String documentId = draftLeaveRequest("1400000.25").id();

        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}/versions/1/fields", documentId))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        JsonNode fields = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));

        JsonNode amount = field(fields, DocumentApiSupport.AMOUNT);
        assertThat(amount.path("type").asText()).isEqualTo("MONEY");
        assertThat(amount.path("amount").isTextual())
                .as("money crosses the wire as a string; a JSON number is an IEEE 754 double "
                        + "in every browser that will read this")
                .isTrue();
        assertThat(amount.path("amount").asText()).isEqualTo("1400000.25");
        assertThat(amount.path("labelKo").asText()).isEqualTo("금액");

        JsonNode days = field(fields, DocumentApiSupport.DAYS);
        assertThat(days.path("type").asText()).isEqualTo("NUMBER");
        assertThat(days.path("number").asText()).isEqualTo("3");

        JsonNode start = field(fields, DocumentApiSupport.START_DATE);
        assertThat(start.path("type").asText()).isEqualTo("DATE");
        assertThat(start.path("date").asText()).isEqualTo("2026-08-20");
    }

    @Test
    @DisplayName("a typed money search compares amounts as amounts, not as text")
    void moneySearchComparesNumerically() throws Exception {
        String over = draftLeaveRequest("1400000").id();
        String under = draftLeaveRequest("900000").id();

        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/field-values")
                .param("fieldId", DocumentApiSupport.AMOUNT)
                .param("amountAtLeast", "1000000")
                .param("limit", "200"))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));

        java.util.List<String> matched = new java.util.ArrayList<String>();
        for (int i = 0; i < page.path("items").size(); i++) {
            matched.add(page.path("items").get(i).path("documentId").asText());
        }
        assertThat(matched).contains(over);
        assertThat(matched)
                .as("900000 sorts after 1400000 as text, so a text comparison would have "
                        + "matched it — the typed column is what makes \"over a million\" mean "
                        + "over a million")
                .doesNotContain(under);
    }

    @Test
    @DisplayName("a field-value search must name exactly one criterion")
    void searchRefusesToGuessTheQuestion() throws Exception {
        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/field-values")
                .param("fieldId", DocumentApiSupport.AMOUNT)
                .param("amountAtLeast", "1000000")
                .param("text", "1000000"))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("one_criterion_required");
    }

    @Test
    @DisplayName("an account with no grant is refused the document, and the list simply omits it")
    void withoutAGrantTheCallerIsRefused() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");
        String strangerId = DocumentApiSupport.seedAccount(accounts, "stranger");
        PermissionPrincipal stranger = DocumentApiSupport.principal(strangerId);

        MvcResult refused = performAs(stranger, MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        JsonNode problem = json.readTree(refused.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("permission_denied");
        assertThat(problem.path("extensions").path("requiredPermission").asText())
                .as("the denial names the permission, so an administrator can grant it")
                .contains("documents.document");

        MvcResult listed = performAs(stranger, MockMvcRequestBuilders
                .get("/api/v1/documents")
                .param("companyId", companyId))
                .andReturn();

        assertThat(listed.getResponse().getStatus())
                .as("a list is filtered row by row rather than refused as a whole, the way the "
                        + "org surface filters people")
                .isEqualTo(200);
        assertThat(json.readTree(listed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("items").size()).isZero();
    }

    @Test
    @DisplayName("an unauthenticated request is a 401 rather than an empty list")
    void anonymousIsRefused() throws Exception {
        // Straight through MockMvc, with nothing in the principal holder: this
        // is the one request in the class that must arrive as nobody.
        MvcResult response = mvc.perform(MockMvcRequestBuilders
                .get("/api/v1/documents").param("companyId", companyId))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the list walks by cursor, and the null cursor is the only end signal")
    void listPagesByCursor() throws Exception {
        draftLeaveRequest("1000");
        draftLeaveRequest("2000");
        draftLeaveRequest("3000");

        JsonNode first = getJson("/api/v1/documents?companyId=" + companyId + "&limit=2");
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.hasNonNull("nextCursor")).isTrue();

        JsonNode second = getJson("/api/v1/documents?companyId=" + companyId + "&limit=2&cursor="
                + first.path("nextCursor").asText());
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.hasNonNull("nextCursor"))
                .as("the end of the collection is the absence of a cursor; a short page is not")
                .isFalse();

        assertThat(idsOf(first)).doesNotContainAnyElementsOf(idsOf(second));
    }

    @Test
    @DisplayName("a malformed cursor is a 400, not a silent restart from page one")
    void malformedCursorIsRefused() throws Exception {
        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents")
                .param("companyId", companyId)
                .param("cursor", "not-a-cursor!!"))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("saving a version without If-Match is refused, and with a stale one is a 412")
    void savingRequiresTheCurrentVersionTag() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");
        MockMultipartFile body = new MockMultipartFile("file", "leave.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentApiSupport.leaveRequestDocx(DocumentApiSupport.filledValues("2000")));

        int missing = perform(MockMvcRequestBuilders
                .multipart("/api/v1/documents/{id}/versions", document.id())
                .file(body)
                .param("format", "DOCX"))
                .andReturn().getResponse().getStatus();
        assertThat(missing)
                .as("428: the client has not implemented concurrency control, which is a "
                        + "different failure from being out of date")
                .isEqualTo(428);

        int stale = perform(MockMvcRequestBuilders
                .multipart("/api/v1/documents/{id}/versions", document.id())
                .file(body)
                .param("format", "DOCX")
                .header("If-Match", "\"99-whatever\""))
                .andReturn().getResponse().getStatus();
        assertThat(stale).isEqualTo(412);

        String tag = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getHeader("ETag");

        MvcResult saved = perform(MockMvcRequestBuilders
                .multipart("/api/v1/documents/{id}/versions", document.id())
                .file(body)
                .param("format", "DOCX")
                .param("defaultCurrencyCode", "KRW")
                .header("If-Match", tag))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(saved.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("supersedesVersionNo").asInt())
                .as("a version is never rewritten; it names the one it replaced")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a value its type cannot read is a 422 naming the field, and stores nothing")
    void anUnreadableValueNamesItsField() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");
        String tag = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getHeader("ETag");

        MvcResult response = perform(MockMvcRequestBuilders
                .multipart("/api/v1/documents/{id}/versions", document.id())
                .file(new MockMultipartFile("file", "leave.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        DocumentApiSupport.leaveRequestDocx(
                                DocumentApiSupport.filledValues("2000"))))
                .param("format", "DOCX")
                .header("If-Match", tag))
                .andReturn();

        assertThat(response.getResponse().getStatus())
                .as("no currency was stated and the money field carries none of its own, so the "
                        + "amount cannot be read; that is a fact about a field, not a bad request")
                .isEqualTo(422);
        JsonNode problem = json.readTree(
                response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("field_value_unreadable");
        assertThat(problem.path("violations").get(0).path("field").asText())
                .isEqualTo(DocumentApiSupport.AMOUNT);
        assertThat(documents.versionsOf(document.id()))
                .as("nothing is stored, so the queryable projection can never disagree with the "
                        + "document")
                .hasSize(1);
    }

    @Test
    @DisplayName("reading a document does not entitle the caller to download it")
    void downloadNeedsTheExportPermission() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");

        String readerId = DocumentApiSupport.seedAccount(accounts, "reader");
        DocumentApiSupport.grant(grants, readerId, DocumentPermissions.DOCUMENT_READ);
        PermissionPrincipal reader = DocumentApiSupport.principal(readerId);

        int metadata = performAs(reader, MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getStatus();
        assertThat(metadata).isEqualTo(200);

        int bytes = performAs(reader, MockMvcRequestBuilders
                .get("/api/v1/documents/{id}/versions/1/content", document.id()))
                .andReturn().getResponse().getStatus();
        assertThat(bytes)
                .as("for a DOCX document the stored bytes are the DOCX export, so serving them "
                        + "under :read would make :export decorative")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("an upload over the size cap is refused before it is read, naming the limit")
    void oversizedUploadIsRefused() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");
        String tag = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getHeader("ETag");

        byte[] tooBig = new byte[(int) Uploads.DOCUMENT_MAX_BYTES + 1];
        MockMultipartFile body = new MockMultipartFile("file", "huge.docx",
                "application/octet-stream", tooBig);

        MvcResult response = perform(MockMvcRequestBuilders
                .multipart("/api/v1/documents/{id}/versions", document.id())
                .file(body)
                .param("format", "DOCX")
                .header("If-Match", tag))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(413);
        JsonNode problem = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("file_too_large");
        assertThat(problem.path("detail").asText()).contains("huge.docx").contains("32 MB");
        assertThat(documents.versionsOf(document.id()))
                .as("nothing was stored: the cap is checked from the part header, before a read")
                .hasSize(1);
    }

    @Test
    @DisplayName("publishing a template whose manifest and document disagree names the field")
    void schemaMismatchNamesTheField() throws Exception {
        String templateId = templates.create(companyId, DocumentApiSupport.code("leave"),
                "휴가신청서", "휴가신청서", "Leave request", accountId).id();
        String tag = perform(MockMvcRequestBuilders
                .get("/api/v1/templates/{id}", templateId))
                .andReturn().getResponse().getHeader("ETag");

        MockMultipartFile body = new MockMultipartFile("files", "leave.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentApiSupport.leaveRequestDocx(DocumentApiSupport.filledValues("1000")));

        MvcResult response = perform(MockMvcRequestBuilders
                .multipart("/api/v1/templates/{id}/versions", templateId)
                .file(body)
                .param("locales", "ko")
                .param("formats", "DOCX")
                .param("schema", DocumentApiSupport.schemaJsonWithUnmatchedField("ghostField"))
                .header("If-Match", tag))
                .andReturn();

        assertThat(response.getResponse().getStatus())
                .as("a mismatch is a validation failure about a field, not a generic 400")
                .isEqualTo(422);
        JsonNode problem = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("schema_mismatch");
        assertThat(problem.path("violations").size()).isEqualTo(1);
        assertThat(problem.path("violations").get(0).path("field").asText())
                .as("the exact field, so the author does not have to diff a docx by hand")
                .isEqualTo("ghostField");
        assertThat(problem.path("violations").get(0).path("code").asText())
                .isEqualTo("field_not_in_document");
    }

    @Test
    @DisplayName("a body diff is refused honestly for docx and produced for mdv")
    void diffIsHonestAboutTheFormat() throws Exception {
        DocumentEntity docx = draftLeaveRequest("1000");
        saveDocxVersion(docx.id(), "2000");

        JsonNode docxDiff = getJson("/api/v1/documents/" + docx.id() + "/versions/2/diff");
        assertThat(docxDiff.path("body").path("available").asBoolean())
                .as("§6.10: mdv is the only format here that can be diffed for real")
                .isFalse();
        assertThat(docxDiff.path("body").path("reason").asText()).contains("mdv");
        assertThat(docxDiff.path("fields").size())
                .as("the field half works for every format, and it is the half a reviewer needs")
                .isEqualTo(1);
        assertThat(docxDiff.path("fields").get(0).path("fieldId").asText())
                .isEqualTo(DocumentApiSupport.AMOUNT);
        assertThat(docxDiff.path("fields").get(0).path("change").asText()).isEqualTo("changed");

        DocumentEntity report = documents.createFromUpload(companyId, "보고서", "월간 보고",
                DocumentApiSupport.mdv("# 보고\n\n매출 1,000\n"), DocumentFormat.MDV, accountId,
                BusinessInstants.resolve("2026-08-18T09:00:00.000"));
        documents.saveVersion(report.id(), DocumentApiSupport.mdv("# 보고\n\n매출 2,000\n지출 500\n"),
                DocumentFormat.MDV, accountId,
                BusinessInstants.resolve("2026-08-18T10:00:00.000"), null);

        JsonNode mdvDiff = getJson("/api/v1/documents/" + report.id() + "/versions/2/diff");
        assertThat(mdvDiff.path("body").path("available").asBoolean()).isTrue();
        assertThat(mdvDiff.path("body").path("addedLines").asInt()).isEqualTo(2);
        assertThat(mdvDiff.path("body").path("removedLines").asInt()).isEqualTo(1);
        assertThat(mdvDiff.path("identicalBytes").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("retiring a document keeps it and takes it out of the list")
    void retirementIsNotDeletion() throws Exception {
        DocumentEntity document = draftLeaveRequest("1000");
        String tag = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getHeader("ETag");

        MvcResult retired = perform(MockMvcRequestBuilders
                .post("/api/v1/documents/{id}/retirement", document.id())
                .header("If-Match", tag))
                .andReturn();
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(retired.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("retired").asBoolean()).isTrue();

        assertThat(idsOf(getJson("/api/v1/documents?companyId=" + companyId)))
                .doesNotContain(document.id());
        assertThat(perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", document.id()))
                .andReturn().getResponse().getStatus())
                .as("the row stays: an approved document is evidence")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("the fidelity matrix is reachable as data, one row per format pair")
    void theFidelityMatrixIsData() throws Exception {
        JsonNode matrix = getJson("/api/v1/documents/fidelity");
        assertThat(matrix.isArray()).isTrue();
        assertThat(matrix.size())
                .as("every pair of registered adapters, including a format with itself")
                .isGreaterThanOrEqualTo(9);

        JsonNode pair = getJson("/api/v1/documents/fidelity?from=docx&to=hwpx");
        assertThat(pair.path("available").asBoolean()).isTrue();
        assertThat(pair.path("fromDisplayName").asText()).contains("DOCX");
        assertThat(pair.path("features").size())
                .as("the whole row for the matrix page, and concerns for the export dialog")
                .isGreaterThan(0);
        assertThat(pair.path("features").get(0).path("describeKo").asText())
                .as("Korean is the default locale, so the row must be readable in it")
                .isNotEmpty();

        MvcResult half = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/fidelity").param("from", "docx"))
                .andReturn();
        assertThat(half.getResponse().getStatus())
                .as("a single format's capabilities are not the question a user has at export "
                        + "time; the pair is")
                .isEqualTo(400);
        assertThat(json.readTree(half.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("pair_needs_both");
    }

    @Test
    @DisplayName("an unknown document is a 404 with a machine-readable code")
    void unknownDocumentIsNotFound() throws Exception {
        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}", DocumentApiSupport.id()))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(404);
        assertThat(response.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("not_found");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private DocumentEntity draftLeaveRequest(String amount) {
        String templateId = templates.create(companyId, DocumentApiSupport.code("leave"),
                "휴가신청서", "휴가신청서", "Leave request", accountId).id();
        Map<String, String> values = DocumentApiSupport.filledValues(amount);
        templates.publishVersion(templateId, DocumentApiSupport.leaveRequestSchema(),
                Immutables.listOf(new TemplateBody("ko", DocumentFormat.DOCX,
                        DocumentApiSupport.leaveRequestDocx(values), "leave.docx")),
                accountId, BusinessInstants.resolve("2026-08-18T09:00:00.000"));

        return documents.createFromTemplate(companyId, templateId, 1, "ko", "휴가신청서 " + amount,
                accountId, BusinessInstants.resolve("2026-08-18T09:30:00.000"), "KRW");
    }

    private void saveDocxVersion(String documentId, String amount) {
        documents.saveVersion(documentId,
                DocumentApiSupport.leaveRequestDocx(DocumentApiSupport.filledValues(amount)),
                DocumentFormat.DOCX, accountId,
                BusinessInstants.resolve("2026-08-18T11:00:00.000"), "KRW");
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult response = perform(MockMvcRequestBuilders.get(url)).andReturn();
        assertThat(response.getResponse().getStatus())
                .as("GET %s said: %s", url, response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode field(JsonNode fields, String fieldId) {
        for (int i = 0; i < fields.size(); i++) {
            if (fieldId.equals(fields.get(i).path("fieldId").asText())) {
                return fields.get(i);
            }
        }
        throw new AssertionError("no field " + fieldId + " in " + fields);
    }

    private static java.util.List<String> idsOf(JsonNode page) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        JsonNode items = page.path("items");
        for (int i = 0; i < items.size(); i++) {
            ids.add(items.get(i).path("id").asText());
        }
        return ids;
    }

    /**
     * Runs a request as the seeded caller.
     *
     * <p>The principal is placed in the holder the authentication filter uses,
     * for the reason {@code DocumentApiSupport#principal} gives: issuing a real
     * API key currently throws inside the auth module. The filter still runs and
     * still clears afterwards, so nothing here weakens what the endpoints check.
     */
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return performAs(caller, request);
    }

    /** Runs a request as somebody else — the caller who has not been granted this. */
    private ResultActions performAs(PermissionPrincipal principal,
            MockHttpServletRequestBuilder request) throws Exception {
        principals.set(principal);
        try {
            return mvc.perform(request);
        } finally {
            principals.clear();
        }
    }

}
