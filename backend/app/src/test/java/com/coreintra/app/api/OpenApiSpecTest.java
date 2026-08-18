package com.coreintra.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Writes {@code docs/api/openapi.json} from the running application.
 *
 * <p>This is a test that has a side effect on purpose, and it is worth being
 * explicit about why. The spec is the contract between the backend and the
 * generated TypeScript client, so it has to be reviewable in a pull request —
 * which means committed, which means something must produce it. Producing it
 * from the booted application rather than from static analysis is what makes it
 * true: it reflects the endpoints that actually exist, with the serialisation
 * Jackson actually performs.
 *
 * <p>CI runs this and then {@code ops/check-api-client-drift.sh} regenerates the
 * client and fails on any diff, so a change to the wire shape cannot merge
 * without the spec and the client changing in the same commit.
 *
 * <h2>Determinism</h2>
 *
 * <p>Keys are sorted on the way out. Without that, an incidental reordering
 * inside springdoc would show up as a spec change in an unrelated pull request,
 * and a diff that cries wolf is a diff nobody reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecTest {

    /** Relative to {@code backend/app}, which is where surefire runs. */
    private static final Path SPEC = Paths.get("..", "..", "docs", "api", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Test
    @DisplayName("the committed OpenAPI document is regenerated from the live application")
    void writeSpec() throws Exception {
        MvcResult response = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andReturn();

        assertThat(response.getResponse().getStatus())
                .as("springdoc must be serving the document; without it the contract cannot be produced")
                .isEqualTo(200);

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        JsonNode document = mapper.readTree(
                response.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(document.path("openapi").asText())
                .as("§10 asks for OpenAPI 3.1; springdoc emits 3.0 unless "
                        + "springdoc.api-docs.version=openapi_3_1 is set")
                .startsWith("3.1");

        String rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
        writeIfChanged(rendered);
    }

    /**
     * Rewrites only on a real change.
     *
     * <p>Touching the file every run would dirty the working tree on every
     * developer's machine and make {@code git status} useless during a build.
     */
    private static void writeIfChanged(String rendered) throws IOException {
        Files.createDirectories(SPEC.getParent());
        if (Files.exists(SPEC)) {
            String existing = new String(Files.readAllBytes(SPEC), StandardCharsets.UTF_8);
            if (existing.equals(rendered)) {
                return;
            }
        }
        Files.write(SPEC, rendered.getBytes(StandardCharsets.UTF_8));
    }
}
