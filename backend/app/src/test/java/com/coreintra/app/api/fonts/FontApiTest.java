package com.coreintra.app.api.fonts;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.documents.DocumentApiSupport;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.api.permission.RequestScopedPrincipal;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.InstalledFont;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The font manager over HTTP, with §6.9's acknowledgement rule as the centre of
 * it.
 *
 * <p>The interesting assertions are the refusals. An acknowledgement that can be
 * left out, or that a client can satisfy with its own wording, records nothing —
 * and the record is the whole point, because we do not verify font licences and
 * say so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FontApiTest {

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
    @Autowired private FontStoreService fonts;

    private String companyId;
    private String accountId;
    private PermissionPrincipal caller;

    @BeforeEach
    void seed() {
        companyId = DocumentApiSupport.seedCompany(companies);
        accountId = DocumentApiSupport.seedAccount(accounts, "fonts");
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.FONT_READ);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.FONT_INSTALL);
        DocumentApiSupport.grant(grants, accountId, DocumentPermissions.FONT_REMOVE);
        caller = DocumentApiSupport.principal(accountId);
    }


    @AfterEach
    void removeWhatThisTestPutIn() {
        DocumentApiSupport.cleanUp(dataSource, companyId);
    }

    @Test
    @DisplayName("an upload with no acknowledgement is refused, and says why in real words")
    void uploadWithoutAcknowledgementIsRefused() throws Exception {
        MvcResult response = upload("나눔고딕", null, null, null);

        assertThat(response.getResponse().getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo("licence_not_acknowledged");
        assertThat(problem.path("detail").asText())
                .contains("do not verify font licences");
        assertThat(installed("나눔고딕")).isNull();
    }

    @Test
    @DisplayName("acknowledging with a false flag is the same as not acknowledging")
    void acknowledgementCannotBeDefaultedIntoBeingTrue() throws Exception {
        MvcResult response = upload("나눔고딕", Boolean.FALSE, LicenceAcknowledgement.VERSION,
                LicenceAcknowledgement.TEXT_KO);

        assertThat(response.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("licence_not_acknowledged");
    }

    @Test
    @DisplayName("a client cannot acknowledge wording of its own invention")
    void theTextMustBeTheOneTheServerShows() throws Exception {
        MvcResult response = upload("나눔고딕", Boolean.TRUE, LicenceAcknowledgement.VERSION,
                "ok");

        assertThat(response.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("licence_text_mismatch");
        assertThat(installed("나눔고딕")).isNull();
    }

    @Test
    @DisplayName("an acknowledgement of superseded wording is refused rather than accepted")
    void staleWordingIsRefused() throws Exception {
        MvcResult response = upload("나눔고딕", Boolean.TRUE, "2019-old-wording",
                LicenceAcknowledgement.TEXT_KO);

        assertThat(response.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText()).isEqualTo("licence_wording_stale");
    }

    @Test
    @DisplayName("a proper acknowledgement installs the font and records who agreed to what")
    void acknowledgementIsRecordedWithTheFont() throws Exception {
        MvcResult response = upload("나눔고딕", Boolean.TRUE, LicenceAcknowledgement.VERSION,
                LicenceAcknowledgement.TEXT_KO);

        assertThat(response.getResponse().getStatus())
                .as("install said: %s", response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        JsonNode font = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(font.path("family").asText()).isEqualTo("나눔고딕");
        assertThat(font.path("source").asText()).isEqualTo("CLIENT_UPLOADED");
        assertThat(font.path("uploadedByAccountId").asText()).isEqualTo(accountId);
        assertThat(font.path("uploadedAt").isNull()).isFalse();
        assertThat(font.path("licenceAcknowledgementText").asText())
                .as("the exact words, versioned, so the record is evidence of what was shown")
                .contains(LicenceAcknowledgement.VERSION)
                .contains(LicenceAcknowledgement.TEXT_KO);
        assertThat(font.path("embeddingPermission").asText())
                .as("we did not read the OS/2 table, and \"we did not look\" must not look like "
                        + "\"it is installable\"")
                .isEqualTo("UNKNOWN");
        assertThat(font.path("scriptCoverage").size()).isEqualTo(1);
        assertThat(font.path("webfontFaceCss").asText()).contains("@font-face");
    }

    @Test
    @DisplayName("the wording endpoint hands back what the upload will demand")
    void theWordingIsFetchable() throws Exception {
        JsonNode wording = json.readTree(perform(MockMvcRequestBuilders
                .get("/api/v1/fonts/licence-acknowledgement"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(wording.path("version").asText()).isEqualTo(LicenceAcknowledgement.VERSION);
        assertThat(wording.path("textKo").asText()).isNotEmpty();
        assertThat(wording.path("textEn").asText()).contains("no warranty or indemnity");
    }

    @Test
    @DisplayName("the list carries what the manager screen has to show, and pages by cursor")
    void listCarriesTheManagerMetadata() throws Exception {
        upload("나눔고딕", Boolean.TRUE, LicenceAcknowledgement.VERSION,
                LicenceAcknowledgement.TEXT_KO);
        upload("나눔명조", Boolean.TRUE, LicenceAcknowledgement.VERSION,
                LicenceAcknowledgement.TEXT_EN);

        JsonNode first = list("1", null);
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(first.path("nextCursor").isNull())
                .as("two uploads and a page of one: there is more, so there is a cursor")
                .isFalse();

        JsonNode second = list("1", first.path("nextCursor").asText());
        assertThat(second.path("items").get(0).path("family").asText())
                .as("the cursor resumes after the first row rather than repeating it")
                .isNotEqualTo(first.path("items").get(0).path("family").asText());

        JsonNode mine = familyNamed(list("200", null), "나눔고딕");
        assertThat(mine.path("style").asText()).isEqualTo("Regular");
        assertThat(mine.path("source").asText()).isEqualTo("CLIENT_UPLOADED");
        assertThat(mine.path("uploadedByAccountId").asText()).isEqualTo(accountId);
        assertThat(mine.path("scriptCoverage").get(0).asText()).isEqualTo("Hang");
        assertThat(mine.path("fontconfigFileName").asText()).endsWith(".ttf");
        assertThat(mine.path("mdvFontConfigEntry").asText())
                .as("one record feeds fontconfig, the browser and mdv, so all three agree")
                .contains("/var/lib/coreintra/fonts/")
                .contains("\"subset\": true");
        assertThat(mine.path("webfontFaceCss").asText())
                .contains("/api/fonts/" + mine.path("blobSha256").asText());
    }

    @Test
    @DisplayName("removal warns with the affected count first, and refuses a stale count")
    void removalWarnsBeforeItHappens() throws Exception {
        String fontId = json.readTree(upload("나눔고딕", Boolean.TRUE,
                LicenceAcknowledgement.VERSION, LicenceAcknowledgement.TEXT_KO)
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asText();

        JsonNode impact = json.readTree(perform(MockMvcRequestBuilders
                .get("/api/v1/fonts/{id}/removal-impact", fontId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(impact.path("family").asText()).isEqualTo("나눔고딕");
        assertThat(impact.path("affectedRenderCount").asLong()).isZero();
        assertThat(impact.path("warningKo").asText()).isNotEmpty();

        int stale = perform(MockMvcRequestBuilders
                .delete("/api/v1/fonts/{id}", fontId)
                .param("acknowledgedAffectedCount", "7"))
                .andReturn().getResponse().getStatus();
        assertThat(stale)
                .as("a font removed under a count the user never saw is a set of approved "
                        + "documents that quietly re-render in another face")
                .isEqualTo(409);

        int removed = perform(MockMvcRequestBuilders
                .delete("/api/v1/fonts/{id}", fontId)
                .param("acknowledgedAffectedCount",
                        String.valueOf(impact.path("affectedRenderCount").asLong())))
                .andReturn().getResponse().getStatus();
        assertThat(removed).isEqualTo(204);
        assertThat(installed("나눔고딕")).isNull();
    }

    @Test
    @DisplayName("installing a font needs documents.font:install, not merely :read")
    void installingNeedsItsOwnPermission() throws Exception {
        String readerId = DocumentApiSupport.seedAccount(accounts, "font-reader");
        DocumentApiSupport.grant(grants, readerId, DocumentPermissions.FONT_READ);
        PermissionPrincipal reader = DocumentApiSupport.principal(readerId);

        MvcResult response = performAs(reader, MockMvcRequestBuilders
                .multipart("/api/v1/fonts")
                .file(new MockMultipartFile("file", "NanumGothic.ttf", "font/ttf",
                        "pretend font".getBytes(com.coreintra.compat.Texts.UTF_8)))
                .param("companyId", companyId)
                .param("family", "나눔고딕")
                .param("fileFormat", "ttf")
                .param("licenceAcknowledged", "true")
                .param("licenceAcknowledgementVersion", LicenceAcknowledgement.VERSION)
                .param("licenceAcknowledgementText", LicenceAcknowledgement.TEXT_KO))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a substitution chain is client-editable and reported back in order")
    void substitutionsAreClientEditable() throws Exception {
        int created = perform(MockMvcRequestBuilders
                .post("/api/v1/fonts/substitutions")
                .contentType("application/json")
                .content("{\"companyId\":\"" + companyId + "\",\"scope\":\"FAMILY\","
                        + "\"scopeKey\":\"함초롬바탕\",\"position\":0,"
                        + "\"fallbackFamily\":\"Pretendard\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(created).isEqualTo(201);

        JsonNode map = json.readTree(perform(MockMvcRequestBuilders
                .get("/api/v1/fonts/substitutions")
                .param("companyId", companyId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(map.path("familyChains").path("함초롬바탕").get(0).asText())
                .as("a client rule replaces the shipped chain rather than appending to it")
                .isEqualTo("Pretendard");
    }

    @Test
    @DisplayName("resolving reports every family, so \"checked and fine\" is distinguishable "
            + "from \"not checked\"")
    void resolveReportsEveryFamily() throws Exception {
        upload("나눔고딕", Boolean.TRUE, LicenceAcknowledgement.VERSION,
                LicenceAcknowledgement.TEXT_KO);

        JsonNode resolutions = json.readTree(perform(MockMvcRequestBuilders
                .get("/api/v1/fonts/resolve")
                .param("companyId", companyId)
                .param("families", "나눔고딕", "함초롬바탕"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(resolutions.size()).isEqualTo(2);
        assertThat(resolutions.get(0).path("requestedFamily").asText()).isEqualTo("나눔고딕");
        assertThat(resolutions.get(0).path("substituted").asBoolean()).isFalse();
        assertThat(resolutions.get(1).path("requestedFamily").asText()).isEqualTo("함초롬바탕");
        assertThat(resolutions.get(1).path("substituted").asBoolean()
                || resolutions.get(1).path("unresolved").asBoolean()).isTrue();
        assertThat(resolutions.get(1).path("reason").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("the webfont route serves the bytes the record points at, and only to a "
            + "caller who may read fonts")
    void theWebfontRouteIsGoverned() throws Exception {
        JsonNode font = json.readTree(upload("나눔고딕", Boolean.TRUE,
                LicenceAcknowledgement.VERSION, LicenceAcknowledgement.TEXT_KO)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        String sha = font.path("blobSha256").asText();

        MvcResult served = perform(MockMvcRequestBuilders
                .get("/api/fonts/{sha}", sha))
                .andReturn();
        assertThat(served.getResponse().getStatus()).isEqualTo(200);
        assertThat(served.getResponse().getContentAsByteArray().length).isGreaterThan(0);

        String strangerId = DocumentApiSupport.seedAccount(accounts, "font-stranger");
        PermissionPrincipal stranger = DocumentApiSupport.principal(strangerId);
        MvcResult refused = performAs(stranger, MockMvcRequestBuilders
                .get("/api/fonts/{sha}", sha))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("not found and not permitted are answered alike, so the route cannot be "
                        + "used to enumerate what is installed")
                .isEqualTo(404);
    }

    private MvcResult upload(String family, Boolean acknowledged, String version, String text)
            throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request =
                MockMvcRequestBuilders.multipart("/api/v1/fonts")
                        .file(new MockMultipartFile("file", family + ".ttf", "font/ttf",
                                ("pretend font " + family)
                                        .getBytes(com.coreintra.compat.Texts.UTF_8)));
        request.param("companyId", companyId)
                .param("family", family)
                .param("style", "Regular")
                .param("fileFormat", "ttf")
                .param("scriptCoverage", "Hang");
        if (acknowledged != null) {
            request.param("licenceAcknowledged", acknowledged.toString());
        }
        if (version != null) {
            request.param("licenceAcknowledgementVersion", version);
        }
        if (text != null) {
            request.param("licenceAcknowledgementText", text);
        }
        return perform(request).andReturn();
    }

    private JsonNode list(String limit, String cursor) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.get("/api/v1/fonts")
                        .param("companyId", companyId)
                        .param("limit", limit)
                        .param("workerFontDirectory", "/var/lib/coreintra/fonts");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return json.readTree(perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode familyNamed(JsonNode page, String family) {
        JsonNode items = page.path("items");
        for (int i = 0; i < items.size(); i++) {
            if (family.equals(items.get(i).path("family").asText())) {
                return items.get(i);
            }
        }
        throw new AssertionError("no font " + family + " in " + items);
    }

    private InstalledFont installed(String family) {
        List<InstalledFont> all = fonts.installedFor(companyId);
        for (InstalledFont font : all) {
            if (family.equals(font.family())) {
                return font;
            }
        }
        return null;
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
