package com.coreintra.app.api.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.permission.RequestScopedPrincipal;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.render.RenderMetadata;
import com.coreintra.documents.service.ConversionJobService;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.RenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Export: the two paths, the labelling, and what happens when the worker is
 * down.
 *
 * <p>There is no conversion worker in a test run, which is the point of most of
 * these: the API has to behave correctly while nothing is converting anything,
 * because that is also what a production outage looks like.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentExportApiTest {

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
    @Autowired private DocumentService documents;
    @Autowired private ConversionJobService jobs;
    @Autowired private RenderService renders;
    @Autowired private FontStoreService fonts;

    private String companyId;
    private String accountId;
    private PermissionPrincipal caller;
    private DocumentEntity document;

    @BeforeEach
    void seed() {
        companyId = DocumentApiSupport.seedCompany(companies);
        accountId = DocumentApiSupport.seedAccount(accounts, "export");
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_READ);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.DOCUMENT_EXPORT);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.FONT_READ);
        caller = DocumentApiSupport.principal(accountId);

        document = documents.createFromUpload(companyId, "지출결의서", "8월 지출결의서",
                DocumentApiSupport.leaveRequestDocx(DocumentApiSupport.filledValues("1400000")),
                DocumentFormat.DOCX, accountId,
                BusinessInstants.resolve("2026-08-18T09:00:00.000"));
    }


    @AfterEach
    void removeWhatThisTestPutIn() {
        DocumentApiSupport.cleanUp(dataSource, companyId);
    }

    @Test
    @DisplayName("a DOCX exported as DOCX is answered immediately and queues no conversion")
    void nativeFormatNeedsNoWorker() throws Exception {
        JsonNode answer = export("DOCX", null);

        assertThat(answer.path("status").asText()).isEqualTo(ExportView.READY);
        assertThat(answer.path("mode").asText()).isEqualTo("immediate");
        assertThat(answer.path("contentUrl").asText())
                .isEqualTo("/api/v1/documents/" + document.id() + "/versions/1/content");
        assertThat(answer.hasNonNull("jobId"))
                .as("sending it round LibreOffice would burn a worker to produce bytes that "
                        + "differ from what was approved")
                .isFalse();

        MvcResult download = perform(MockMvcRequestBuilders
                .get(answer.path("contentUrl").asText()))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentAsByteArray().length).isGreaterThan(0);
        assertThat(download.getResponse().getHeader("Content-Disposition"))
                .as("a Korean title must survive the header in both forms")
                .contains("filename*=UTF-8''");
    }

    @Test
    @DisplayName("a PDF export with nothing archived answers 202 with a job id and a poll URL")
    void conversionIsAJobAndSaysSo() throws Exception {
        MvcResult response = exportRaw("PDF", null);

        assertThat(response.getResponse().getStatus())
                .as("202: the answer is a job, and the status code says so as well as the body")
                .isEqualTo(202);
        JsonNode answer = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(answer.path("status").asText()).isEqualTo(ExportView.QUEUED);
        assertThat(answer.path("mode").asText()).isEqualTo("asynchronous");
        assertThat(answer.path("jobState").asText()).isEqualTo("QUEUED");
        assertThat(answer.path("pollUrl").asText())
                .isEqualTo("/api/v1/documents/" + document.id() + "/export/jobs/"
                        + answer.path("jobId").asText());
        assertThat(answer.hasNonNull("contentUrl"))
                .as("no bytes exist yet, and a URL that 409s would look like one that works")
                .isFalse();

        JsonNode polled = json.readTree(perform(MockMvcRequestBuilders
                .get(answer.path("pollUrl").asText()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(polled.path("jobId").asText()).isEqualTo(answer.path("jobId").asText());
    }

    @Test
    @DisplayName("asking twice joins the one job instead of starting a second LibreOffice")
    void exportIsIdempotent() throws Exception {
        String first = json.readTree(exportRaw("PDF", null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("jobId").asText();
        String second = json.readTree(exportRaw("PDF", null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("jobId").asText();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("the .doc target comes back labelled legacy and lossy, in both languages")
    void docIsLabelledLegacy() throws Exception {
        JsonNode answer = json.readTree(
                exportRaw("DOC", null).getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(answer.path("legacy").asBoolean()).isTrue();
        assertThat(answer.path("legacyNoteEn").asText()).contains("Legacy");
        assertThat(answer.path("legacyNoteKo").asText()).contains("구형");
    }

    @Test
    @DisplayName("the fidelity row for the direction comes back with the export")
    void fidelityRowTravelsWithTheExport() throws Exception {
        JsonNode toHwpx = json.readTree(
                exportRaw("HWPX", null).getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode fidelity = toHwpx.path("fidelity");

        assertThat(fidelity.path("available").asBoolean()).isTrue();
        assertThat(fidelity.path("fromFormat").asText()).isEqualTo("docx");
        assertThat(fidelity.path("toFormat").asText()).isEqualTo("hwpx");
        assertThat(fidelity.path("concerns").size())
                .as("docx to hwpx is not lossless, and the dialog has to be able to say what goes")
                .isGreaterThan(0);
        assertThat(fidelity.path("concerns").get(0).path("describeKo").asText()).isNotEmpty();

        JsonNode toPdf = json.readTree(exportRaw("PDF", null).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(toPdf.path("fidelity").path("available").asBoolean())
                .as("nothing reads a PDF back, so there is no honest round-trip row")
                .isFalse();
        assertThat(toPdf.path("fidelity").path("unavailableReason").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("a font the store does not have is named in the export response, never "
            + "substituted silently")
    void missingFontIsNamed() throws Exception {
        JsonNode answer = json.readTree(exportRaw("PDF",
                "\"requiredFonts\":[\"함초롬바탕\"],").getResponse().getContentAsString(StandardCharsets.UTF_8));

        JsonNode warnings = answer.path("fontWarnings");
        assertThat(warnings.size()).isEqualTo(1);
        assertThat(warnings.get(0).path("requestedFamily").asText()).isEqualTo("함초롬바탕");
        assertThat(warnings.get(0).path("substituted").asBoolean()
                || warnings.get(0).path("unresolved").asBoolean())
                .as("함초롬바탕 is Hancom-licensed and is never bundled, so it must resolve to "
                        + "something else and say so")
                .isTrue();
        assertThat(warnings.get(0).path("reason").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("substitutions recorded on the archived render are surfaced at export time")
    void recordedSubstitutionsAreSurfaced() throws Exception {
        FontResolver resolver = fonts.resolverFor(companyId);
        RenderMetadata metadata = RenderMetadata.builder()
                .rendererVersion("LibreOffice 7.6.4.1")
                .locale("ko")
                .fontResolutions(Immutables.listOf(resolver.resolve("함초롬바탕", null)))
                .build();
        byte[] pdf = "%PDF-1.7 pretend".getBytes(com.coreintra.compat.Texts.UTF_8);
        renders.archive(companyId, document.id(), 1, RenderFormat.PDF, metadata, pdf,
                "application/pdf");

        JsonNode answer = export("PDF", null);

        assertThat(answer.path("status").asText()).isEqualTo(ExportView.READY);
        assertThat(answer.path("rendererVersion").asText()).isEqualTo("LibreOffice 7.6.4.1");
        assertThat(answer.path("recordedSubstitutions").size())
                .as("the substitution is part of the render's record, not a warning that scrolls "
                        + "past")
                .isEqualTo(1);
        assertThat(answer.path("recordedSubstitutions").get(0).asText()).contains("함초롬바탕");

        MvcResult download = perform(MockMvcRequestBuilders
                .get(answer.path("contentUrl").asText()))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(pdf);
    }

    @Test
    @DisplayName("a conversion worker that never answers produces an actionable error and no "
            + "file at all")
    void workerDownIsAnErrorAndNeverATruncatedFile() throws Exception {
        String jobId = json.readTree(exportRaw("PDF", null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("jobId").asText();

        // Play a worker that claims jobs and dies. The queue hands out the
        // oldest claimable job rather than the one this test made, so the loop
        // fails whatever it is given until ours has spent its attempts — which
        // is exactly what a container in a restart loop does to a queue.
        for (int attempt = 0; attempt < 40; attempt++) {
            if (jobs.find(jobId).get().state() == ConversionJobEntity.State.ABANDONED) {
                break;
            }
            Optional<ConversionJobEntity> leased =
                    jobs.lease("worker-1", Duration.ofMinutes(5), OffsetDateTime.now());
            if (!leased.isPresent()) {
                break;
            }
            jobs.fail(leased.get().id(), "WORKER_UNREACHABLE",
                    "connect ECONNREFUSED converter:2003", OffsetDateTime.now());
        }
        assertThat(jobs.find(jobId).get().state())
                .as("three attempts and it is abandoned: a document that crashes the worker is "
                        + "not retried until the end of time")
                .isEqualTo(ConversionJobEntity.State.ABANDONED);

        MvcResult polled = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}/export/jobs/{job}", document.id(), jobId))
                .andReturn();
        assertThat(polled.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(polled.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("conversion_failed");
        assertThat(problem.path("detail").asText())
                .as("the operator needs the worker's own words and what to do next")
                .contains("WORKER_UNREACHABLE")
                .contains("ECONNREFUSED")
                .contains("Nothing partial was written");

        MvcResult download = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}/versions/1/export/content", document.id())
                .param("format", "PDF"))
                .andReturn();
        assertThat(download.getResponse().getStatus())
                .as("a 200 with an empty body is indistinguishable from a truncated download")
                .isEqualTo(409);
        assertThat(json.readTree(download.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("export_not_ready");
        assertThat(download.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("%PDF");
        assertThat(renders.rendersOf(document.id(), 1))
                .as("a failed job names no render, so there is nothing half-written to serve")
                .isEmpty();
    }

    @Test
    @DisplayName("waiting is offered, bounded, and answers with a job when the worker does not "
            + "come back")
    void waitingIsBounded() throws Exception {
        long before = System.currentTimeMillis();
        MvcResult response = exportRaw("PDF", "\"waitMillis\":60000,");
        long elapsed = System.currentTimeMillis() - before;

        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        assertThat(elapsed)
                .as("a request thread held open for a minute is one nobody else can have; the "
                        + "cap is %d ms", DocumentExportController.MAX_WAIT_MILLIS)
                .isLessThan(DocumentExportController.MAX_WAIT_MILLIS + 15_000L);
    }

    @Test
    @DisplayName("export needs its own permission: reading the document is not enough")
    void exportNeedsTheExportPermission() throws Exception {
        String readerId = DocumentApiSupport.seedAccount(accounts, "reader");
        DocumentApiSupport.grant(grants, readerId, DocumentPermissions.DOCUMENT_READ);
        PermissionPrincipal reader = DocumentApiSupport.principal(readerId);

        MvcResult response = performAs(reader, MockMvcRequestBuilders
                .post("/api/v1/documents/{id}/export", document.id())
                .contentType("application/json")
                .content("{\"format\":\"PDF\"}"))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a job id from another document cannot be polled through this one")
    void jobsAreCheckedAgainstTheirDocument() throws Exception {
        String jobId = json.readTree(exportRaw("PDF", null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("jobId").asText();

        DocumentEntity other = documents.createFromUpload(companyId, "보고서", "다른 문서",
                DocumentApiSupport.mdv("# 다른 문서\n"), DocumentFormat.MDV, accountId,
                BusinessInstants.resolve("2026-08-18T09:00:00.000"));

        MvcResult response = perform(MockMvcRequestBuilders
                .get("/api/v1/documents/{id}/export/jobs/{job}", other.id(), jobId))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(404);
    }

    private JsonNode export(String format, String extraJson) throws Exception {
        MvcResult response = exportRaw(format, extraJson);
        assertThat(response.getResponse().getStatus())
                .as("export said: %s", response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult exportRaw(String format, String extraJson) throws Exception {
        String body = "{" + (extraJson == null ? "" : extraJson)
                + "\"format\":\"" + format + "\"}";
        return perform(MockMvcRequestBuilders
                .post("/api/v1/documents/{id}/export", document.id())
                .contentType("application/json")
                .content(body))
                .andReturn();
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
