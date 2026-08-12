package com.libranet.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA = "#/components/schemas/ApiError";

    @Bean
    OpenAPI libranetOpenApi() {
        String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("LibraNet API")
                        .version("v1")
                        .description("Multi-tenant SaaS library platform. Tenant-plane endpoints under "
                                + "/api/v1/tenant/** additionally require a validated X-Library-Id header "
                                + "(library UID like LIB-XXXXXX); the server verifies the caller's membership. "
                                + "Every error — across all endpoints — uses the ApiError envelope "
                                + "{timestamp,status,code,message,details,traceId}: stable machine codes for clients, "
                                + "no internals leaked. Cross-tenant resource IDs answer 404, never 403. "
                                + "Standard error responses per endpoint are documented below; endpoint entries "
                                + "list only the codes that can actually occur there.")
                        .contact(new Contact().name("LibraNet Platform"))
                        .license(new License().name("Proprietary — all rights reserved")))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components()
                        .addSecuritySchemes(scheme, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    /**
     * Phase-11 OpenAPI polish: every operation documents the error responses it can actually
     * produce, all referencing the shared ApiError envelope. Never clobbers an explicit
     * {@code @ApiResponse} (add-if-absent).
     */
    @Bean
    OpenApiCustomizer standardErrorResponses() {
        return openApi -> {
            // register the shared error envelope on the FINAL model — schemas added to the
            // @Bean's own Components survive less reliably through springdoc's merge.
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas("ApiError", apiErrorSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
                String path = entry.getKey();
                boolean anonymousApi = isAnonymous(path);
                boolean authApi = path.startsWith("/api/v1/auth/");
                for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> e :
                        entry.getValue().readOperationsMap().entrySet()) {
                    PathItem.HttpMethod method = e.getKey();
                    io.swagger.v3.oas.models.Operation op = e.getValue();
                    ApiResponses responses = op.getResponses();
                    if (responses == null) {
                        responses = new ApiResponses();
                        op.setResponses(responses);
                    }
                    addIfAbsent(responses, "400",
                            "Bad request — missing/invalid parameters or body validation failed (VALIDATION_FAILED)");
                    if (!anonymousApi || authApi) {
                        addIfAbsent(responses, "401",
                                "Unauthenticated — missing/invalid/expired access token or credentials");
                    }
                    if (path.startsWith("/api/v1/tenant/") || path.startsWith("/api/v1/platform/")) {
                        addIfAbsent(responses, "403",
                                "Forbidden — membership role, tenant, ownership or plan-limit guard refused the call");
                    }
                    if (path.contains("{")) {
                        addIfAbsent(responses, "404",
                                "Not found — also the answer for cross-tenant IDs (isolation: 404, never 403)");
                    }
                    if ((method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT
                                    || method == PathItem.HttpMethod.PATCH)
                            && (path.startsWith("/api/v1/tenant/") || path.startsWith("/api/v1/platform/"))) {
                        addIfAbsent(responses, "409",
                                "Conflict with current state — duplicates, queue/state rules, DOWNGRADE_BLOCKED");
                    }
                    if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/mfa/verify")) {
                        addIfAbsent(responses, "423",
                                "Account locked after repeated failures — details.retryAfterSeconds tells when to retry");
                    }
                    if (authApi) {
                        addIfAbsent(responses, "429",
                                "Rate limited — authentication endpoints are throttled per IP+account");
                    }
                }
            }
        };
    }

    /** Anonymous surface: public book pages/media/reviews, directory browse, invitation preview. */
    private static boolean isAnonymous(String path) {
        if (path.startsWith("/api/v1/public")) {
            return true;
        }
        if (path.equals("/api/v1/libraries") || path.equals("/api/v1/libraries/{slug}")
                || path.startsWith("/api/v1/join/")) {
            return true;
        }
        return false; // note: POST /api/v1/libraries/{slug}/join is authenticated (not in this set)
    }

    private static Schema<?> apiErrorSchema() {
        return new ObjectSchema()
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("status", new IntegerSchema().description("HTTP status (mirrored)"))
                .addProperty("code", new StringSchema().description(
                        "Stable machine code, e.g. VALIDATION_FAILED, TENANT_HEADER_REQUIRED, PLAN_LIMIT_EXCEEDED"))
                .addProperty("message", new StringSchema().description("Human-safe summary"))
                .addProperty("details", new ObjectSchema().description(
                        "Optional machine-readable extras, e.g. {retryAfterSeconds} on 423/429"))
                .addProperty("traceId", new StringSchema().description(
                        "Correlates with server logs; quote it in support tickets"))
                .example(Map.of(
                        "timestamp", "2026-08-11T12:00:00Z",
                        "status", 403,
                        "code", "PLAN_LIMIT_EXCEEDED",
                        "message", "AI calls exceed this month's plan limit",
                        "details", Map.of("feature", "SEMANTIC_SEARCH", "used", 500, "limit", 500),
                        "traceId", "tr_01J8EXAMPLE"));
    }

    private static void addIfAbsent(ApiResponses responses, String status, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        responses.addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA)))));
    }
}
