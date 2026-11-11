package cn.dormlink;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.repairs.RepairService;
import cn.dormlink.reporting.StatisticsService;
import cn.dormlink.media.FileService;
import cn.dormlink.dispatch.DispatchRepository;
import cn.dormlink.dispatch.DispatchSignals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** Public HTTP behavior, with JDBC fault injection at the persistence boundary. */
@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:refactor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
        "app.seed=true", "app.cookie-secure=false", "app.ai.api-key=", "app.files=target/refactor-uploads"
})
class BackendRefactorIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OpenGaussTestDatabase.configure(registry, "refactor");
    }

    @LocalServerPort int port;
    @MockitoSpyBean Db db;
    @Autowired AuthService auth;
    @Autowired RepairService repairs;
    @Autowired FileService files;
    @Autowired StatisticsService statistics;
    @Autowired DispatchRepository dispatch;
    @Autowired DispatchSignals signals;
    @MockitoSpyBean cn.dormlink.mobility.MobilityService mobility;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = new JsonMapper();

    @AfterEach
    void resetFaults() {
        reset(db);
        reset(mobility);
    }

    HttpResponse<String> request(String token, String method, String path, Object body, String key)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Idempotency-Key", key);
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("Content-Type", "application/json").method(method,
                body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String token(String username) throws Exception {
        var response = request(null, "POST", "/auth/login", Map.of("username", username,
                "password", "Demo@123456", "clientType", "MOBILE"), UUID.randomUUID().toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("token").asText();
    }

    @Test
    void failedSessionRevocationRollsBackPasswordChange() throws Exception {
        String token = token("student2");
        String userId = auth.authenticate(token).id();
        String oldHash = db.one("SELECT password_hash FROM sys_user WHERE id=?", userId).get("passwordHash").toString();
        doThrow(new IllegalStateException("injected session-store failure")).when(db)
                .update(startsWith("UPDATE auth_session SET revoked_at="), any(Object[].class));
        var response = request(token, "POST", "/auth/change-password",
                Map.of("oldPassword", "Demo@123456", "newPassword", "Changed@123456"), UUID.randomUUID().toString());
        reset(db);
        String actual = db.one("SELECT password_hash FROM sys_user WHERE id=?", userId).get("passwordHash").toString();
        // Restore this isolated fixture even when the pre-refactor implementation fails the assertion.
        db.update("UPDATE sys_user SET password_hash=? WHERE id=?", oldHash, userId);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(actual).isEqualTo(oldHash);
        assertThat(auth.authenticate(token)).isNotNull();
    }

    @Test
    void cachedCommandCannotReplayAfterBuildingPermissionRevocation() throws Exception {
        String token = token("manager1");
        String userId = auth.authenticate(token).id();
        var student = db.one("SELECT h.student_id,h.building_id FROM residence h JOIN manager_building mb"
                + " ON mb.building_id=h.building_id WHERE h.checked_out_at IS NULL AND mb.manager_user_id=? LIMIT 1", userId);
        String key = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("visitedStudentId", student.get("studentId"),
                "visitorName", "权限重放测试", "phone", "13800000000", "purpose", "测试",
                "confirmDuplicate", true);
        var created = request(token, "POST", "/visitors", payload, key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        db.update("DELETE FROM manager_building WHERE manager_user_id=? AND building_id=?", userId, student.get("buildingId"));
        try {
            var replay = request(token, "POST", "/visitors", payload, key);
            assertThat(replay.statusCode()).as(replay.body()).isEqualTo(403);
            assertThat(replay.body()).doesNotContain("权限重放测试");
        } finally {
            db.update("INSERT INTO manager_building(manager_user_id,building_id) VALUES(?,?)", userId, student.get("buildingId"));
        }
    }

    @Test
    void authenticatedPrincipalRetainsValidatedSessionAndChannel() throws Exception {
        String token = token("student1");
        var actor = auth.authenticate(token);
        assertThat(actor.sessionId()).isNotBlank();
        assertThat(actor.clientType()).isEqualTo("MOBILE");
        assertThat(db.one("SELECT user_id FROM auth_session WHERE id=?", actor.sessionId()).get("userId"))
                .isEqualTo(actor.id());
    }

    @Test
    void repairPageQueryCountIsIndependentOfPageLength() throws Exception {
        String student = token("student1");
        for (int index = 0; index < 5; index++) {
            var created = request(student, "POST", "/repairs", Map.of("title", "批量列表测试" + index,
                    "description", "测试补充行", "category", "OTHER", "priority", "NORMAL",
                    "contactName", "测试学生", "contactPhone", "13800000000", "reportImageIds", List.of()),
                    UUID.randomUUID().toString());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        }
        var actor = auth.authenticate(token("manager1"));
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        long thread = Thread.currentThread().threadId();
        var queries = new AtomicInteger();
        doAnswer(invocation -> {
            if (Thread.currentThread().threadId() == thread) queries.incrementAndGet();
            return invocation.callRealMethod();
        }).when(db).rows(any(String.class), any(Object[].class));
        try {
            repairs.list(Map.of("pageSize", "100"));
            assertThat(queries.get()).as("rows-query budget for a complete repair page").isLessThanOrEqualTo(4);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void imageProcessingRunsOutsideDatabaseTransactionAndThumbnailIsSmaller() throws Exception {
        String token = token("student1");
        var actor = auth.authenticate(token);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, List.of()));
        try {
            var image = new java.awt.image.BufferedImage(1600, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            var multipart = new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png", bytes.toByteArray()) {
                @Override
                public byte[] getBytes() throws java.io.IOException {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            .as("decode/resize must not occupy a JDBC transaction").isFalse();
                    return super.getBytes();
                }
            };
            @SuppressWarnings("unchecked")
            var asset = (Map<String, Object>) files.upload(multipart, "REPORT");
            var full = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + asset.get("url")))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            var thumbnail = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + asset.get("thumbnailUrl")))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(full.statusCode()).isEqualTo(200);
            assertThat(thumbnail.statusCode()).isEqualTo(200);
            var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.body()));
            assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(360);
            assertThat(thumbnail.body().length).isLessThan(full.body().length);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void statisticsTransferAggregatesRatherThanHistoricalRows() throws Exception {
        String token = token("manager1");
        var actor = auth.authenticate(token);
        var student = db.one("SELECT h.student_id FROM residence h JOIN manager_building mb"
                + " ON mb.building_id=h.building_id WHERE h.checked_out_at IS NULL AND mb.manager_user_id=? LIMIT 1", actor.id());
        var created = request(token, "POST", "/visitors", Map.of("visitedStudentId", student.get("studentId"),
                "visitorName", "统计聚合样本", "phone", "13800000000", "purpose", "测试", "confirmDuplicate", true), UUID.randomUUID().toString());
        String sourceId = json.readTree(created.body()).path("data").path("id").asText();
        for (int index = 0; index < 100; index++) {
            db.update("INSERT INTO visitor_registration(visitor_name,phone,visited_student_id,student_name,room_id,room_no,building_id,building_name,purpose,remark,arrived_at,operator_id)"
                    + " SELECT visitor_name,phone,visited_student_id,student_name,room_id,room_no,building_id,building_name,purpose,remark,arrived_at,operator_id FROM visitor_registration WHERE id=?", sourceId);
        }
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, List.of()));
        long thread = Thread.currentThread().threadId();
        var transferred = new AtomicInteger();
        doAnswer(invocation -> {
            var rows = (List<?>) invocation.callRealMethod();
            if (Thread.currentThread().threadId() == thread) transferred.addAndGet(rows.size());
            return rows;
        }).when(db).rows(any(String.class), any(Object[].class));
        try {
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) statistics.visitors(Map.of());
            assertThat(((Number) result.get("total")).longValue()).isGreaterThanOrEqualTo(101);
            assertThat(transferred.get()).as("summary transfer volume independent of historical row count").isLessThanOrEqualTo(10);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void boardReadIsConsistentWithoutBlockingRevisionWriter() throws Exception {
        var entered = new CountDownLatch(1);
        var updated = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = executor.submit(() -> dispatch.readPlan(() -> {
                long before = db.count("SELECT data_revision FROM dispatch_revision WHERE id=1");
                entered.countDown();
                try {
                    assertThat(updated.await(2, TimeUnit.SECONDS)).as("board reads must not hold a global write lock").isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                long after = db.count("SELECT data_revision FROM dispatch_revision WHERE id=1");
                assertThat(after).as("one board must observe one database snapshot").isEqualTo(before);
                return true;
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> {
                new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                        db.update("UPDATE dispatch_revision SET data_revision=data_revision+1 WHERE id=1"));
                updated.countDown();
            });
            assertThat(reader.get(5, TimeUnit.SECONDS)).isTrue();
            writer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void committedDispatchFactsAreDurableBeforeRecalculationFlush() {
        String reason = "TEST_DURABLE_" + UUID.randomUUID().toString().substring(0, 8);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                signals.changed(null, null, reason));
        assertThat(db.count("SELECT COUNT(*) FROM dispatch_event WHERE event_type=?", reason)).isEqualTo(1);
    }

    @Test
    void effectiveTravelChangesRollbackAndExpiryInvalidatePlanningEligibility() throws Exception {
        var actor = auth.authenticate(token("manager1"));
        var link = db.one("SELECT from_building_id,to_building_id,minutes FROM dispatch_travel ORDER BY from_building_id,to_building_id LIMIT 1");
        String from = link.get("fromBuildingId").toString(), to = link.get("toBuildingId").toString();
        String baseline = dispatch.eligibilityFingerprint();
        db.update("INSERT INTO mobility_travel_model(from_building_id,to_building_id,minutes,sample_count,session_count,enabled,version,updated_at)"
                + " VALUES(?,?,?,5,3,TRUE,0,?)", from, to, ((Number) link.get("minutes")).intValue() + 2,
                java.sql.Timestamp.from(java.time.Instant.now()));
        try {
            String learned = dispatch.eligibilityFingerprint();
            assertThat(learned).as("new effective travel model must trigger replanning").isNotEqualTo(baseline);
            mobility.rollbackTravelModel(actor, from, to, 0);
            assertThat(dispatch.eligibilityFingerprint()).as("manual rollback restores the baseline planning input").isEqualTo(baseline);
            db.update("UPDATE mobility_travel_model SET enabled=TRUE,updated_at=? WHERE from_building_id=? AND to_building_id=?",
                    java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(31L * 86400)), from, to);
            assertThat(dispatch.eligibilityFingerprint()).as("expired learned costs must fall back and replan").isEqualTo(baseline);
            db.update("UPDATE mobility_travel_model SET updated_at=? WHERE from_building_id=? AND to_building_id=?",
                    java.sql.Timestamp.from(java.time.Instant.now()), from, to);
            assertThat(dispatch.eligibilityFingerprint()).isEqualTo(learned);
        } finally {
            db.update("DELETE FROM mobility_model_revision WHERE from_building_id=? AND to_building_id=?", from, to);
            db.update("DELETE FROM mobility_travel_model WHERE from_building_id=? AND to_building_id=?", from, to);
        }
    }

    @Test
    void devicePositionBarrierPreventsOldManualFallbackUntilANewerExplicitManualSetting() throws Exception {
        String managerId = auth.authenticate(token("manager1")).id();
        var manual = db.one("SELECT * FROM dispatch_manager WHERE user_id=?", managerId);
        var buildings = db.rows("SELECT building_id FROM manager_building WHERE manager_user_id=? ORDER BY building_id", managerId);
        String a = buildings.getFirst().get("buildingId").toString(), b = buildings.getLast().get("buildingId").toString();
        java.time.Instant observed = java.time.Instant.now();
        db.update("UPDATE dispatch_manager SET position_building_id=?,position_floor_no=3,position_updated_at=? WHERE user_id=?",
                a, java.sql.Timestamp.from(observed.minusSeconds(300)), managerId);
        try {
            doReturn(Map.of(managerId, new cn.dormlink.mobility.MobilityModels.PlannerContext(b, null, observed,
                    "LOW", "GPS识别楼栋，楼层未知"))).when(mobility).plannerContexts(any(java.time.Instant.class));
            var gps = dispatch.snapshot(false);
            var visible = gps.managers().stream().filter(row -> managerId.equals(row.get("userId"))).findFirst().orElseThrow();
            assertThat(visible.get("positionBuildingId")).isEqualTo(b);
            assertThat(visible.get("positionFloorNo")).isNull();
            assertThat(gps.input().workers().stream().filter(worker -> managerId.equals(worker.id())).findFirst().orElseThrow().start().buildingId())
                    .as("unknown floor cannot become a fabricated planner starting point").isNull();

            doReturn(Map.of(managerId, new cn.dormlink.mobility.MobilityModels.PlannerContext(null, null, observed,
                    "LOW", "设备观测失效"))).when(mobility).plannerContexts(any(java.time.Instant.class));
            var expired = dispatch.snapshot(false).managers().stream().filter(row -> managerId.equals(row.get("userId"))).findFirst().orElseThrow();
            assertThat(expired.get("positionBuildingId")).as("expired observation must not resurrect older A3").isNull();
            db.update("UPDATE dispatch_manager SET position_building_id=?,position_floor_no=4,position_updated_at=? WHERE user_id=?",
                    a, java.sql.Timestamp.from(observed.plusSeconds(1)), managerId);
            var newer = dispatch.snapshot(false).managers().stream().filter(row -> managerId.equals(row.get("userId"))).findFirst().orElseThrow();
            assertThat(newer.get("positionBuildingId")).as("a later explicit manual correction may replace the barrier").isEqualTo(a);
            assertThat(((Number) newer.get("positionFloorNo")).intValue()).isEqualTo(4);
        } finally {
            db.update("UPDATE dispatch_manager SET position_building_id=?,position_floor_no=?,position_updated_at=? WHERE user_id=?",
                    manual.get("positionBuildingId"), manual.get("positionFloorNo"), manual.get("positionUpdatedAt") == null ? null
                            : java.sql.Timestamp.from(java.time.Instant.parse(manual.get("positionUpdatedAt").toString())), managerId);
        }
    }
}
