package cn.dormlink.mobility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@ActiveProfiles(resolver = MobilityHttpIntegrationTest.Database.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mobility_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
            "app.seed=true",
            "app.cookie-secure=false",
            "app.ai.api-key=",
            "app.dispatch.automatic=false",
            "app.files=target/mobility-uploads"
        })
class MobilityHttpIntegrationTest {
    public static class Database implements ActiveProfilesResolver {
        @Override
        public String[] resolve(Class<?> type) {
            String url = System.getenv("OPENGAUSS_TEST_URL");
            return new String[] {url == null || url.isBlank() ? "local" : "opengauss"};
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String template = System.getenv("OPENGAUSS_TEST_URL");
        if (template == null || template.isBlank()) {
            return;
        }
        String url = template.replace("{suite}", "mobility_http");
        if (!url.matches("jdbc:opengauss://[^/]+/dormlink_mobility_http_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Use isolated dormlink_mobility_http_test");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("OPENGAUSS_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("OPENGAUSS_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.opengauss.Driver");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/opengauss/schema.sql");
        registry.add("app.migration-location", () -> "db/opengauss/V2__dynamic_dispatch.sql");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.datasource.hikari.data-source-properties.use_boolean", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MobilityService service;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    HttpResponse<String> call(String token, String method, String path, Object body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(String name) throws Exception {
        var response =
                call(
                        null,
                        "POST",
                        "/auth/login",
                        Map.of(
                                "username",
                                name,
                                "password",
                                "Demo@123456",
                                "clientType",
                                "MOBILE"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("token").asText();
    }

    JsonNode start(String token) throws Exception {
        var current =
                json.readTree(call(token, "GET", "/mobility/session", null).body())
                        .path("data")
                        .path("session");
        Map<String, Object> body = new HashMap<>();
        body.put("clientInstanceId", UUID.randomUUID().toString());
        body.put("consentVersion", "mobility-v1");
        if (!current.isNull()
                && Set.of("ACTIVE", "PAUSED").contains(current.path("state").asText())) {
            body.put("takeover", true);
            body.put("expectedVersion", current.path("version").asInt());
        }
        var response = call(token, "POST", "/mobility/sessions", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    @Test
    void logoutEventCommitsWorkingSessionRevocationAndClearsExactCoordinates() throws Exception {
        String token = login("manager1");
        JsonNode view = start(token), session = view.path("session");
        String workId = session.path("id").asText();
        var batch =
                Map.of(
                        "sourceEpoch",
                        session.path("sourceEpoch").asInt(),
                        "batchId",
                        UUID.randomUUID().toString(),
                        "samples",
                        List.of(
                                Map.of(
                                        "sequence",
                                        1,
                                        "streamId",
                                        "http-stream",
                                        "observedAt",
                                        java.time.Instant.now().toString(),
                                        "elapsedMs",
                                        0,
                                        "latitude",
                                        30,
                                        "longitude",
                                        120,
                                        "accuracyMeters",
                                        5,
                                        "motion",
                                        "UNKNOWN")));
        assertThat(
                        call(token, "POST", "/mobility/sessions/" + workId + "/samples", batch)
                                .statusCode())
                .isEqualTo(200);
        assertThat(call(token, "POST", "/auth/logout", Map.of()).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM field_work_session WHERE id=?",
                                String.class,
                                workId))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT latitude FROM manager_context_snapshot WHERE session_id=?",
                                Double.class,
                                workId))
                .isNull();
        assertThat(call(token, "GET", "/mobility/session", null).statusCode()).isEqualTo(401);
    }

    @Test
    void requiredFieldsAndExplicitNullsMatchTheSharedContract() throws Exception {
        String token = login("manager1");
        JsonNode view = start(token), session = view.path("session");
        assertThat(session.has("lastSampleAt")).isTrue();
        assertThat(session.path("lastSampleAt").isNull()).isTrue();
        assertThat(view.path("context").has("latitude")).isTrue();
        assertThat(view.path("context").path("latitude").isNull()).isTrue();
        String path = "/mobility/sessions/" + session.path("id").asText();
        assertThat(
                        call(
                                        token,
                                        "POST",
                                        path + "/pause",
                                        Map.of("sourceEpoch", session.path("sourceEpoch").asInt()))
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        call(
                                        token,
                                        "POST",
                                        path + "/anchor-confirmations",
                                        Map.of(
                                                "sourceEpoch",
                                                session.path("sourceEpoch").asInt(),
                                                "anchorId",
                                                "missing",
                                                "source",
                                                "QR"))
                                .statusCode())
                .isEqualTo(400);
        assertThat(call(token, "GET", "/mobility/travel-models", null).statusCode()).isEqualTo(200);
    }

    @Test
    void chunkedOversizedSamplesAreRejectedBeforeTheBodyIsBuffered() throws Exception {
        String token = login("manager1");
        JsonNode session = start(token).path("session");
        byte[] body =
                ("{\"padding\":\"" + "x".repeat(17000) + "\"}").getBytes(StandardCharsets.UTF_8);
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/mobility/sessions/"
                                                + session.path("id").asText()
                                                + "/samples"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () -> new java.io.ByteArrayInputStream(body)))
                        .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(413);
    }

    @Test
    void studentsCanReadOnlyTheirOwnResidenceAnchorsAndCannotPublishWorkLocations()
            throws Exception {
        String token = login("student1");
        assertThat(call(token, "GET", "/mobility/anchors", null).statusCode()).isEqualTo(200);
        assertThat(call(token, "GET", "/mobility/session", null).statusCode()).isEqualTo(403);
        assertThat(
                        call(
                                        token,
                                        "POST",
                                        "/mobility/sessions",
                                        Map.of(
                                                "clientInstanceId",
                                                "student",
                                                "consentVersion",
                                                "mobility-v1"))
                                .statusCode())
                .isEqualTo(403);
    }
}
