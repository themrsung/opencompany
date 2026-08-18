package com.coreintra.app.api.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document, which is the contract rather than a by-product.
 *
 * <p>It is generated from the code, committed at {@code docs/api/openapi.json},
 * and CI regenerates it and fails on any diff. That means a change to the wire
 * shape shows up in the pull request that causes it, and the generated
 * TypeScript client cannot silently fall out of step with the server.
 *
 * <p>Note the <em>two</em> security schemes and what each is for. A browser
 * sends an HttpOnly cookie it cannot read; a service account, a client module
 * or an MCP client sends a scoped API key as a bearer token. There is no third
 * way in, and neither scheme is privileged over the other: §10 requires that if
 * an account may do something in the UI it may do it over the API, and the
 * reverse.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String SESSION_SCHEME = "sessionCookie";
    public static final String API_KEY_SCHEME = "apiKey";

    @Bean
    public OpenAPI coreIntraOpenApi(
            @Value("${coreintra.installation-name:CoreIntra}") String installationName) {
        return new OpenAPI()
                .info(new Info()
                        .title(installationName + " API")
                        .version("v1")
                        .description(description())
                        .license(new License().name("MIT")))
                // Relative, deliberately: the same artefact is served from a
                // vendor-managed box and from a client's own network, and an
                // absolute server URL baked into the spec would be wrong on one
                // of them.
                .addServersItem(new Server().url("/api/v1").description("This installation"))
                .components(new Components()
                        .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("ci_at")
                                .description("Short-lived, HttpOnly, SameSite=Strict. Set by "
                                        + "sign-in and rotated by the refresh endpoint. Opaque "
                                        + "and checked against the session registry on every "
                                        + "request, so a revocation takes effect immediately."))
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("A scoped API key, ci_<prefix>_<secret>. Its scopes "
                                        + "narrow the account's permissions and never widen "
                                        + "them.")))
                // Applied globally so that an endpoint is authenticated unless it
                // says otherwise, rather than the other way round.
                .addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }

    private static String description() {
        return "Every capability of this intranet is an endpoint here; the web application is "
                + "simply the first client. Access is governed purely by the calling account's "
                + "permissions.\n\n"
                + "**Amounts** cross the wire as exact decimal strings and are never JSON "
                + "numbers. Optional thousands separators are accepted on input; `1e3`, `.5` "
                + "and `1.` are rejected. Rounding happens at display and never on write.\n\n"
                + "**Times** that mean something to the business are business instants, "
                + "`YYYY-MM-DDT[-]HH:MM:SS.mmm`, with no timezone and no trailing `Z` — a `Z` is "
                + "rejected. The clock face runs from `-24:00:00` to `+48:00:00`, so a shift "
                + "ending at three in the morning is `27:00` on the business day it began. "
                + "Ordering is by business date first and offset second: "
                + "`2026-08-30T26:01:00.000` precedes `2026-08-31T-03:22:00.000`. Never sort the "
                + "wire strings lexically.\n\n"
                + "**Collections** are cursor-paginated. `nextCursor` being null is the only "
                + "end-of-collection signal; a short page does not mean the end.\n\n"
                + "**Mutations** of versioned resources require `If-Match`. Writes that create "
                + "money or approvals accept an `Idempotency-Key`; replaying a key with a "
                + "different body is rejected rather than served the earlier response.\n\n"
                + "**Errors** are RFC 7807 problem+json with a stable machine-readable `code`, "
                + "and every validation failure is returned at once.";
    }
}
