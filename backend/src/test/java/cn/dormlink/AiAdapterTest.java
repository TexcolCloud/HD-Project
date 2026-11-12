package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dormlink.shared.jdbc.Db;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Protocol fixture, not a claim that a real supplier/key has been exercised. */
@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:aitest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
            "app.seed=true",
            "app.cookie-secure=false",
            "app.ai.api-key=fixture-not-a-real-key",
            "app.ai.model=fixture-model",
            "app.files=target/ai-test-uploads"
        })
class AiAdapterTest {
    static final JsonMapper JSON = new JsonMapper();
    static final AtomicReference<String> CAPTURE = new AtomicReference<>();
    static final AtomicReference<String> ANSWER = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<CountDownLatch> PROVIDER_ENTERED = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> PROVIDER_RELEASE = new AtomicReference<>();
    static final ExecutorService PROVIDER_THREADS =
            Executors.newCachedThreadPool(
                    task -> {
                        var thread = new Thread(task, "ai-fixture");
                        thread.setDaemon(true);
                        return thread;
                    });
    static final HttpServer MODEL;

    static {
        try {
            MODEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MODEL.setExecutor(PROVIDER_THREADS);
            MODEL.createContext(
                    "/chat/completions",
                    exchange -> {
                        int call = CALLS.incrementAndGet();
                        CAPTURE.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        if (call == 1 && PROVIDER_RELEASE.get() != null) {
                            PROVIDER_ENTERED.get().countDown();
                            try {
                                PROVIDER_RELEASE.get().await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        byte[] out =
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "message",
                                                                Map.of("content", ANSWER.get()))),
                                                "usage",
                                                Map.of(
                                                        "prompt_tokens",
                                                        20,
                                                        "completion_tokens",
                                                        15)));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, out.length);
                        exchange.getResponseBody().write(out);
                        exchange.close();
                    });
            MODEL.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry r) {
        OpenGaussTestDatabase.configure(r, "ai");
        r.add("app.ai.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        MODEL.stop(0);
        PROVIDER_THREADS.shutdownNow();
    }

    @LocalServerPort int port;
    @Autowired Db db;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void isolateAttemptWindow() {
        db.update(
                "UPDATE ai_invocation SET created_at=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3 * 86400)));
        CALLS.set(0);
        PROVIDER_ENTERED.set(null);
        PROVIDER_RELEASE.set(null);
    }

    HttpResponse<String> post(String path, Object body, String token) throws Exception {
        return post(path, body, token, UUID.randomUUID().toString());
    }

    HttpResponse<String> post(String path, Object body, String token, String requestKey)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", requestKey);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(
                b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String login() throws Exception {
        return login("student1");
    }

    String login(String username) throws Exception {
        return JSON.readTree(
                        post(
                                        "/auth/login",
                                        Map.of(
                                                "username",
                                                username,
                                                "password",
                                                "Demo@123456",
                                                "clientType",
                                                "MOBILE"),
                                        null)
                                .body())
                .path("data")
                .path("token")
                .asText();
    }

    @Test
    void sameAiRequestKeyReturnsTheCompleteCachedResultWithoutAnotherProviderCall()
            throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "第一次生成",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅依据输入")));
        String token = login("manager1"), key = UUID.randomUUID().toString();
        var body = Map.of("consentToExternalAnalysis", true);
        var first = post("/ai/manager/repairs/1", body, token, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "不应该再次生成",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅依据输入")));
        var second = post("/ai/manager/repairs/1", body, token, key);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(JSON.readTree(second.body()).path("data"))
                .isEqualTo(JSON.readTree(first.body()).path("data"));
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void managerProviderNeverReceivesFormattedPhoneOrStudentIdentityInActualFreeText()
            throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "请核实故障现象",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅依据输入")));
        var original = db.one("SELECT title,description FROM repair_order WHERE id=1");
        var event = db.one("SELECT id,note FROM repair_event WHERE order_id=1 ORDER BY id LIMIT 1");
        String identity = "陈同学学号2026-0001，联系138 0000 0001，或(138)0000-0001；全角１３８ ００００ ０００１";
        try {
            db.update(
                    "UPDATE repair_order SET title=?,description=? WHERE id=1",
                    identity,
                    "故障描述包含身份：" + identity);
            db.update(
                    "UPDATE repair_event SET note=? WHERE id=?",
                    "进度中的身份：" + identity,
                    event.get("id"));
            var response =
                    post(
                            "/ai/manager/repairs/1",
                            Map.of("consentToExternalAnalysis", true),
                            login("manager1"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(CAPTURE.get())
                    .doesNotContain(
                            "陈同学",
                            "2026-0001",
                            "138 0000 0001",
                            "(138)0000-0001",
                            "13800000001",
                            "20260001",
                            "１３８ ００００ ０００１");
        } finally {
            db.update(
                    "UPDATE repair_order SET title=?,description=? WHERE id=1",
                    original.get("title"),
                    original.get("description"));
            db.update(
                    "UPDATE repair_event SET note=? WHERE id=?",
                    event.get("note"),
                    event.get("id"));
        }
    }

    @Test
    void concurrentSameKeyDoesNotStartAnotherProviderRequestAndDifferentBodyIsRejected()
            throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "并发测试建议",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅依据输入")));
        String token = login("manager1"), key = UUID.randomUUID().toString();
        var body = Map.of("consentToExternalAnalysis", true);
        PROVIDER_ENTERED.set(new CountDownLatch(1));
        PROVIDER_RELEASE.set(new CountDownLatch(1));
        try (var clients = Executors.newSingleThreadExecutor()) {
            var first = clients.submit(() -> post("/ai/manager/repairs/1", body, token, key));
            try {
                assertThat(PROVIDER_ENTERED.get().await(5, TimeUnit.SECONDS)).isTrue();
                var duplicate = post("/ai/manager/repairs/1", body, token, key);
                assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
                assertThat(duplicate.body()).contains("AI_REQUEST_RUNNING");
                var changed =
                        post(
                                "/ai/manager/repairs/1",
                                Map.of("consentToExternalAnalysis", true, "includeImages", true),
                                token,
                                key);
                assertThat(changed.statusCode()).isEqualTo(409);
                assertThat(changed.body()).contains("IDEMPOTENCY_KEY_REUSED");
                assertThat(CALLS.get()).isEqualTo(1);
            } finally {
                PROVIDER_RELEASE.get().countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void failedAndUnconfirmedKeysNeverSilentlyRepeatTheProviderCall() throws Exception {
        ANSWER.set("not JSON");
        String token = login(), key = UUID.randomUUID().toString();
        var body =
                Map.of(
                        "description",
                        "门把手松动",
                        "imageIds",
                        List.of(),
                        "consentToExternalAnalysis",
                        true);
        var first = post("/ai/repair-suggestions", body, token, key);
        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(first.body()).contains("AI_UNAVAILABLE");
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "title",
                                "门把手松动",
                                "category",
                                "OTHER",
                                "summary",
                                "请检查松动现象",
                                "uncertainty",
                                "原因待核实",
                                "questions",
                                List.of(),
                                "riskTags",
                                List.of())));
        var repeat = post("/ai/repair-suggestions", body, token, key);
        assertThat(repeat.statusCode()).isEqualTo(409);
        assertThat(repeat.body()).contains("AI_REQUEST_FAILED");
        assertThat(CALLS.get()).isEqualTo(1);
        var row = db.one("SELECT * FROM idempotency_record WHERE request_key=?", key);
        var state = JSON.readTree(row.get("responseData").toString());
        String unknownKey = UUID.randomUUID().toString();
        db.insert(
                "INSERT INTO"
                    + " idempotency_record(user_id,route_key,request_key,request_hash,response_data,created_at)"
                    + " VALUES(?,?,?,?,?,?)",
                row.get("userId"),
                row.get("routeKey"),
                unknownKey,
                row.get("requestHash"),
                JSON.writeValueAsString(
                        Map.of("state", "RUNNING", "scopeHash", state.path("scopeHash").asText())),
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(95)));
        var unknown = post("/ai/repair-suggestions", body, token, unknownKey);
        assertThat(unknown.statusCode()).isEqualTo(409);
        assertThat(unknown.body()).contains("AI_RESULT_UNKNOWN");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void cachedManagerBriefIsNotReturnedAfterItsAuthorizedBuildingRangeChanges() throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "授权范围简报",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅依据输入")));
        String token = login("manager1"), key = UUID.randomUUID().toString();
        var body = Map.of("consentToExternalAnalysis", true);
        var first = post("/ai/manager/brief", body, token, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("data").path("evidence").size()).isPositive();
        String
                manager =
                        db.one("SELECT id FROM sys_user WHERE username='manager1'")
                                .get("id")
                                .toString(),
                building = db.one("SELECT id FROM building WHERE code='A'").get("id").toString();
        try {
            db.update(
                    "DELETE FROM manager_building WHERE manager_user_id=? AND building_id=?",
                    manager,
                    building);
            var repeat = post("/ai/manager/brief", body, token, key);
            assertThat(repeat.statusCode()).isEqualTo(409);
            assertThat(repeat.body()).contains("AI_REQUEST_FAILED").doesNotContain("BX-DEMO-001");
            assertThat(CALLS.get()).isEqualTo(1);
        } finally {
            db.update(
                    "INSERT INTO manager_building(manager_user_id,building_id) VALUES(?,?)",
                    manager,
                    building);
        }
    }

    @Test
    void managerAnalysisUsesAuthorizedEvidenceAndServerFactsWithoutChangingTheOrder()
            throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "请核查洗手台漏水现象。",
                                "findings",
                                List.of("描述未明确漏点。"),
                                "suggestedActions",
                                List.of("联系学生确认到访时段。"),
                                "questions",
                                List.of("关闭水龙头后是否仍漏水？"),
                                "uncertainty",
                                "无法确定原因。",
                                "facts",
                                List.of(Map.of("label", "伪造统计", "value", "999")))));
        long orders = db.count("SELECT COUNT(*) FROM repair_order");
        long events = db.count("SELECT COUNT(*) FROM repair_event");
        var response =
                post(
                        "/ai/manager/repairs/1",
                        Map.of("consentToExternalAnalysis", true),
                        login("manager1"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data = JSON.readTree(response.body()).path("data");
        assertThat(data.path("requiresHumanReview").asBoolean()).isTrue();
        assertThat(data.path("evidence").get(0).path("orderId").asText()).isEqualTo("1");
        assertThat(data.path("facts").toString()).doesNotContain("999");
        assertThat(CAPTURE.get()).doesNotContain("13800000001", "20260001", "陈同学");
        assertThat(db.count("SELECT COUNT(*) FROM repair_order")).isEqualTo(orders);
        assertThat(db.count("SELECT COUNT(*) FROM repair_event")).isEqualTo(events);
    }

    @Test
    void managerBriefCannotExposeAnotherBuildingOrAcceptStudentRequests() throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "当前范围无未结事项。",
                                "findings",
                                List.of(),
                                "suggestedActions",
                                List.of(),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅基于给定记录。",
                                "evidence",
                                List.of(Map.of("orderId", "999")))));
        String manager = login("manager2");
        assertThat(
                        post(
                                        "/ai/manager/repairs/1",
                                        Map.of("consentToExternalAnalysis", true),
                                        manager)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        post(
                                        "/ai/manager/brief",
                                        Map.of("consentToExternalAnalysis", true),
                                        login())
                                .statusCode())
                .isEqualTo(403);
        var response =
                post("/ai/manager/brief", Map.of("consentToExternalAnalysis", true), manager);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("data").path("evidence").size()).isZero();
        assertThat(CAPTURE.get()).doesNotContain("洗手台下方管道漏水", "13800000001", "陈同学");
    }

    @Test
    void managerCanRecordOnlyOwnSuccessfulAdviceWithIdempotentFeedback() throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "summary",
                                "核对门锁情况",
                                "findings",
                                List.of("需现场核对"),
                                "suggestedActions",
                                List.of("联系学生"),
                                "questions",
                                List.of(),
                                "uncertainty",
                                "仅为建议")));
        String token = login("manager1");
        var generated =
                post("/ai/manager/repairs/1", Map.of("consentToExternalAnalysis", true), token);
        assertThat(generated.statusCode()).as(generated.body()).isEqualTo(200);
        String suggestion =
                JSON.readTree(generated.body()).path("data").path("suggestionId").asText();
        long version = db.count("SELECT version FROM repair_order WHERE id=1");
        String key = UUID.randomUUID().toString();
        var body =
                Map.of(
                        "suggestionId",
                        suggestion,
                        "decision",
                        "MODIFIED",
                        "reason",
                        "先核对学生时间",
                        "outcome",
                        "经人工核对后安排办理");
        var first = post("/ai/manager/repairs/1/feedback", body, token, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var replay = post("/ai/manager/repairs/1/feedback", body, token, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("data").path("id").asText())
                .isEqualTo(JSON.readTree(replay.body()).path("data").path("id").asText());
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM ai_advice_feedback WHERE suggestion_id=?",
                                suggestion))
                .isEqualTo(1);
        assertThat(db.count("SELECT version FROM repair_order WHERE id=1")).isEqualTo(version);
        assertThat(post("/ai/manager/repairs/1/feedback", body, login("manager2")).statusCode())
                .isEqualTo(404);
        assertThat(post("/ai/manager/repairs/1/feedback", body, login("student1")).statusCode())
                .isEqualTo(403);
        String other =
                db.one("SELECT id FROM sys_user WHERE username='manager2'").get("id").toString();
        String building =
                db.one("SELECT building_id FROM repair_order WHERE id=1")
                        .get("buildingId")
                        .toString();
        db.update(
                "INSERT INTO manager_building(manager_user_id,building_id) VALUES(?,?)",
                other,
                building);
        try {
            String otherToken = login("manager2");
            assertThat(post("/ai/manager/repairs/1/feedback", body, otherToken).statusCode())
                    .isEqualTo(404);
            var history =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/api/v1/ai/manager/repairs/1/feedback"))
                                    .header("Authorization", "Bearer " + otherToken)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
            assertThat(
                            JSON.readTree(history.body())
                                    .path("data")
                                    .path("items")
                                    .path(0)
                                    .path("canFollowUp")
                                    .asBoolean())
                    .isFalse();
        } finally {
            db.update(
                    "DELETE FROM manager_building WHERE manager_user_id=? AND building_id=?",
                    other,
                    building);
        }
    }

    @Test
    void sendsRealHttpProtocolAndReturnsHumanReviewedSuggestionWithoutCreatingOrder()
            throws Exception {
        ANSWER.set(
                JSON.writeValueAsString(
                        Map.of(
                                "title",
                                "洗手台疑似漏水",
                                "category",
                                "UTILITIES",
                                "summary",
                                "观察到洗手台下方滴水。",
                                "uncertainty",
                                "无法确定漏点",
                                "questions",
                                List.of("关闭水龙头后还滴水吗？"),
                                "riskTags",
                                List.of("WATER_ACCUMULATION"))));
        long before = db.count("SELECT COUNT(*) FROM repair_order");
        String token = login();
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                png);
        String boundary = "AiFixtureImage";
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"usage\"\r\n\r\n"
                                + "REPORT\r\n"
                                + "--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"fixture.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes());
        bytes.write(png.toByteArray());
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes());
        var upload =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(200);
        String id = JSON.readTree(upload.body()).path("data").path("id").asText();
        var response =
                post(
                        "/ai/repair-suggestions",
                        Map.of(
                                "description",
                                "洗手台下方滴水",
                                "imageIds",
                                List.of(id),
                                "consentToExternalAnalysis",
                                true),
                        token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(
                        JSON.readTree(response.body())
                                .path("data")
                                .path("requiresHumanReview")
                                .asBoolean())
                .isTrue();
        assertThat(CAPTURE.get())
                .contains("fixture-model", "image_url", "data:image/jpeg;base64,")
                .doesNotContain("13800000001", "20260001", "HD_SESSION");
        assertThat(db.count("SELECT COUNT(*) FROM repair_order")).isEqualTo(before);
    }

    @Test
    void malformedModelJsonFailsClosedAndKeepsAnAttemptRecord() throws Exception {
        ANSWER.set("not a JSON object");
        var response =
                post(
                        "/ai/repair-suggestions",
                        Map.of(
                                "description",
                                "门把手松动",
                                "imageIds",
                                List.of(),
                                "consentToExternalAnalysis",
                                true),
                        login());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("AI_UNAVAILABLE");
        assertThat(db.count("SELECT COUNT(*) FROM ai_invocation WHERE status='FAILED'"))
                .isPositive();
    }
}
