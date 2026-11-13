package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import cn.dormlink.followup.ServiceMessageService;
import cn.dormlink.shared.jdbc.Db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Exercises the public follow-up workflow, including ownership and idempotent receipts. */
@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:followup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
            "app.seed=true",
            "app.cookie-secure=false",
            "app.ai.api-key=",
            "app.dispatch.automatic=false",
            "app.files=target/followup-uploads"
        })
class ServiceFollowupIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OpenGaussTestDatabase.configure(registry, "followup");
    }

    @LocalServerPort int port;
    @MockitoSpyBean Db db;
    @Autowired ServiceMessageService messages;
    @Autowired javax.sql.DataSource dataSource;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    HttpResponse<String> request(String token, String method, String path, Object body, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String token, String method, String path, Object body) throws Exception {
        var response = request(token, method, path, body, UUID.randomUUID().toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    String token(String username) throws Exception {
        return ok(
                        null,
                        "POST",
                        "/auth/login",
                        Map.of(
                                "username",
                                username,
                                "password",
                                "Demo@123456",
                                "clientType",
                                "MOBILE"))
                .path("token")
                .asText();
    }

    JsonNode inspection(
            String manager, String room, String round, Instant at, String related, int score)
            throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("roomId", room);
        body.put("inspectionDate", at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
        body.put("round", round);
        body.put("inspectedAt", at.toString());
        for (String field : List.of("floorScore", "deskScore", "bedScore", "bathroomScore")) {
            body.put(field, score);
        }
        body.put("issueNote", "QA fictional inspection");
        body.put("imageIds", List.of());
        if (related != null) {
            body.put("relatedInspectionId", related);
        }
        return ok(manager, "POST", "/hygiene-inspections", body);
    }

    String uploadEvidence(String token) throws Exception {
        var image =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        String boundary = "qa-" + UUID.randomUUID();
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"usage\"\r\n\r\n"
                                + "REMEDIATION\r\n"
                                + "--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"qa-proof.jpg\"\r\n"
                                + "Content-Type: image/jpeg\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(output.toByteArray());
        bytes.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("id").asText();
    }

    @Test
    void messageReceiptIsPrivateAndIdempotent() throws Exception {
        String student = token("student1"),
                manager = token("manager1"),
                outsider = token("student2");
        ok(
                student,
                "POST",
                "/repairs",
                Map.of(
                        "title",
                        "QA followup " + UUID.randomUUID(),
                        "description",
                        "fictional broken handle",
                        "category",
                        "FURNITURE",
                        "priority",
                        "NORMAL",
                        "contactName",
                        "QA",
                        "contactPhone",
                        "13800000000",
                        "reportImageIds",
                        List.of()));
        JsonNode items = null;
        for (int i = 0; i < 25; i++) {
            items = ok(manager, "GET", "/messages?unreadOnly=true", null).path("items");
            if (!items.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(items).isNotNull();
        assertThat(items.size()).isGreaterThan(0);
        String id = items.path(0).path("id").asText();
        int version = items.path(0).path("version").asInt();
        assertThat(
                        request(
                                        outsider,
                                        "POST",
                                        "/messages/" + id + "/read",
                                        Map.of("expectedVersion", version),
                                        "private")
                                .statusCode())
                .isEqualTo(404);
        var first =
                ok(
                        manager,
                        "POST",
                        "/messages/" + id + "/read",
                        Map.of("expectedVersion", version));
        var second =
                ok(
                        manager,
                        "POST",
                        "/messages/" + id + "/read",
                        Map.of("expectedVersion", version));
        assertThat(first.path("readAt").asText()).isNotEmpty();
        assertThat(second.path("version").asInt()).isEqualTo(first.path("version").asInt());
    }

    @Test
    void remediationRequiresResponsibleStudentAndQualifyingRecheck() throws Exception {
        String manager = token("manager1"),
                student = token("student1"),
                outsider = token("student2");
        JsonNode me = ok(student, "GET", "/auth/me", null);
        String room = ok(student, "GET", "/me/room", null).path("id").asText();
        Instant at = Instant.now().minusSeconds(90);
        String round = "QA" + UUID.randomUUID().toString().substring(0, 8);
        var initial = inspection(manager, room, round, at, null, 10);
        var task =
                ok(
                        manager,
                        "POST",
                        "/hygiene-remediations",
                        Map.of(
                                "inspectionId",
                                initial.path("id").asText(),
                                "responsibleStudentId",
                                me.path("studentId").asText(),
                                "issueNote",
                                "请清理地面",
                                "dueAt",
                                Instant.now().plusSeconds(86400).toString()));
        String id = task.path("id").asText();
        assertThat(
                        ok(manager, "GET", "/hygiene-remediations?inspectionId=999999999", null)
                                .path("total")
                                .asInt())
                .isZero();
        assertThat(
                        ok(
                                        manager,
                                        "GET",
                                        "/hygiene-remediations?inspectionId="
                                                + initial.path("id").asText(),
                                        null)
                                .path("items")
                                .path(0)
                                .path("id")
                                .asText())
                .isEqualTo(id);
        assertThat(request(outsider, "GET", "/hygiene-remediations/" + id, null, null).statusCode())
                .isEqualTo(404);
        String evidence = uploadEvidence(student);
        task =
                ok(
                        student,
                        "POST",
                        "/hygiene-remediations/" + id + "/feedback",
                        Map.of(
                                "expectedVersion",
                                0,
                                "feedbackNote",
                                "已整理，申请复查",
                                "imageIds",
                                List.of(evidence)));
        assertThat(task.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(task.path("evidenceImages").size()).isEqualTo(1);
        assertThat(
                        request(student, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(manager, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(outsider, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        request(
                                        manager,
                                        "POST",
                                        "/hygiene-remediations/" + id + "/review",
                                        Map.of(
                                                "expectedVersion",
                                                1,
                                                "approved",
                                                true,
                                                "reviewNote",
                                                "凭空关闭"),
                                        UUID.randomUUID().toString())
                                .statusCode())
                .isEqualTo(400);
        var recheck =
                inspection(
                        manager,
                        room,
                        "RECHECK-" + round,
                        at.plusSeconds(30),
                        initial.path("id").asText(),
                        23);
        var closed =
                ok(
                        manager,
                        "POST",
                        "/hygiene-remediations/" + id + "/review",
                        Map.of(
                                "expectedVersion",
                                1,
                                "approved",
                                true,
                                "reviewNote",
                                "现场复查合格",
                                "relatedRecheckId",
                                recheck.path("id").asText()));
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(
                        request(
                                        student,
                                        "POST",
                                        "/hygiene-remediations/" + id + "/feedback",
                                        Map.of(
                                                "expectedVersion",
                                                2,
                                                "feedbackNote",
                                                "不能再改",
                                                "imageIds",
                                                List.of()),
                                        UUID.randomUUID().toString())
                                .statusCode())
                .isEqualTo(409);
        ok(
                manager,
                "POST",
                "/hygiene-inspections/" + recheck.path("id").asText() + "/void",
                Map.of("expectedVersion", 0, "reason", "验收依据录错，重新复核"));
        var reopened = ok(manager, "GET", "/hygiene-remediations/" + id, null);
        assertThat(reopened.path("status").asText()).isEqualTo("OPEN");
        var adjusted =
                ok(
                        manager,
                        "PATCH",
                        "/hygiene-remediations/" + id,
                        Map.of(
                                "expectedVersion",
                                3,
                                "responsibleStudentId",
                                me.path("studentId").asText(),
                                "dueAt",
                                Instant.now().plusSeconds(172800).toString(),
                                "reason",
                                "重新约定复查期限"));
        assertThat(adjusted.path("version").asInt()).isEqualTo(4);
        assertThat(adjusted.path("evidenceImages").size()).isZero();
        assertThat(
                        request(student, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(200);
        var cancelled =
                ok(
                        manager,
                        "POST",
                        "/hygiene-remediations/" + id + "/cancel",
                        Map.of("expectedVersion", 4, "reason", "虚构验收任务结束，不冒充验收通过"));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        ok(
                manager,
                "POST",
                "/hygiene-inspections/" + initial.path("id").asText() + "/void",
                Map.of("expectedVersion", 0, "reason", "原检查作废，保留授权审计凭证"));
        assertThat(
                        request(manager, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(student, "GET", "/files/" + evidence + "/thumbnail", null, null)
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void hygieneCorrectionCannotPointForwardOrCreateCycle() throws Exception {
        String manager = token("manager1"), student = token("student1");
        String room = ok(student, "GET", "/me/room", null).path("id").asText();
        String round = "QA" + UUID.randomUUID().toString().substring(0, 8);
        Instant at = Instant.now().minusSeconds(120);
        var first = inspection(manager, room, round, at, null, 10);
        var second =
                inspection(
                        manager,
                        room,
                        "RECHECK-" + round,
                        at.plusSeconds(20),
                        first.path("id").asText(),
                        20);
        var body = new LinkedHashMap<String, Object>();
        body.put("roomId", room);
        body.put("inspectionDate", first.path("inspectionDate").asText());
        body.put("round", round);
        body.put("inspectedAt", at.toString());
        body.put("expectedVersion", 0);
        for (String field : List.of("floorScore", "deskScore", "bedScore", "bathroomScore")) {
            body.put(field, 10);
        }
        body.put("issueNote", "QA");
        body.put("imageIds", List.of());
        body.put("correctionReason", "QA逆序关联");
        body.put("relatedInspectionId", second.path("id").asText());
        assertThat(
                        request(
                                        manager,
                                        "PATCH",
                                        "/hygiene-inspections/" + first.path("id").asText(),
                                        body,
                                        UUID.randomUUID().toString())
                                .statusCode())
                .isEqualTo(400);
    }

    @Test
    void feedbackCannotInventAnAiSuggestion() throws Exception {
        String manager = token("manager1");
        String id = ok(manager, "GET", "/repairs", null).path("items").path(0).path("id").asText();
        assertThat(
                        request(
                                        manager,
                                        "POST",
                                        "/ai/manager/repairs/" + id + "/feedback",
                                        Map.of(
                                                "suggestionId",
                                                "999999999",
                                                "decision",
                                                "ADOPTED",
                                                "reason",
                                                "伪造建议",
                                                "outcome",
                                                ""),
                                        UUID.randomUUID().toString())
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void messageFailureRollsBackRepairAndAllowsSameKeyRetry() throws Exception {
        String student = token("student1");
        String title = "QA atomic followup " + UUID.randomUUID();
        var body =
                Map.of(
                        "title",
                        title,
                        "description",
                        "fictional repair",
                        "category",
                        "OTHER",
                        "priority",
                        "NORMAL",
                        "contactName",
                        "QA",
                        "contactPhone",
                        "13800000000",
                        "reportImageIds",
                        List.of());
        String key = UUID.randomUUID().toString();
        long count = db.count("SELECT COUNT(*) FROM repair_order");
        doThrow(new IllegalStateException("injected outbox failure"))
                .when(db)
                .update(startsWith("INSERT INTO service_message"), any(Object[].class));
        HttpResponse<String> failed;
        try {
            failed = request(student, "POST", "/repairs", body, key);
        } finally {
            reset(db);
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(db.count("SELECT COUNT(*) FROM repair_order")).isEqualTo(count);
        var retried = request(student, "POST", "/repairs", body, key);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        var replay = request(student, "POST", "/repairs", body, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(db.count("SELECT COUNT(*) FROM repair_order WHERE title=?", title)).isEqualTo(1);
        String id = json.readTree(retried.body()).path("data").path("id").asText();
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM service_message WHERE source_key=?",
                                "repair:" + id + ":0:SUBMIT"))
                .isGreaterThan(0);
    }

    @Test
    void overdueSweepAdvancesBeyondFirstBatchAndNeverFakesCheckout() throws Exception {
        String manager = token("manager1"), student = token("student1");
        String studentId = ok(student, "GET", "/auth/me", null).path("studentId").asText();
        String first = null;
        for (int i = 0; i < 101; i++) {
            var visit =
                    ok(
                            manager,
                            "POST",
                            "/visitors",
                            Map.of(
                                    "visitorName",
                                    "QA sweep " + i,
                                    "phone",
                                    "13800000000",
                                    "visitedStudentId",
                                    studentId,
                                    "purpose",
                                    "fictional batch",
                                    "confirmDuplicate",
                                    true));
            String id = visit.path("id").asText();
            if (first == null) {
                first = id;
            }
            db.update(
                    "UPDATE visitor_registration SET arrived_at=? WHERE id=?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(13 * 3600L)),
                    id);
        }
        messages.followUp();
        messages.followUp();
        messages.followUp();
        assertThat(
                        db.count(
                                "SELECT COUNT(DISTINCT business_id) FROM service_message WHERE"
                                        + " kind='VISITOR_CHECKOUT_REMINDER' AND business_id>=?",
                                first))
                .isEqualTo(101);
        long count =
                db.count(
                        "SELECT COUNT(*) FROM service_message WHERE"
                                + " kind='VISITOR_CHECKOUT_REMINDER' AND business_id>=?",
                        first);
        messages.followUp();
        messages.followUp();
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM service_message WHERE"
                                        + " kind='VISITOR_CHECKOUT_REMINDER' AND business_id>=?",
                                first))
                .isEqualTo(count);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM visitor_registration WHERE id>=? AND left_at"
                                        + " IS NULL",
                                first))
                .isEqualTo(101);
    }

    @Test
    void residentLookupFiltersExactRoom() throws Exception {
        String manager = token("manager1"), student = token("student1");
        String room = ok(student, "GET", "/me/room", null).path("id").asText();
        var students =
                ok(manager, "GET", "/students?roomId=" + room + "&pageSize=100", null)
                        .path("items");
        assertThat(students.size()).isGreaterThan(0);
        for (var item : students) {
            assertThat(item.path("roomId").asText()).isEqualTo(room);
        }
    }

    @Test
    void recheckWaitsForRoomMutationThenRejectsVoidedSource() throws Exception {
        String manager = token("manager1"), student = token("student1");
        String room = ok(student, "GET", "/me/room", null).path("id").asText();
        String round = "QA" + UUID.randomUUID().toString().substring(0, 8);
        Instant at = Instant.now().minusSeconds(120);
        var original = inspection(manager, room, round, at, null, 10);
        try (var connection = dataSource.getConnection();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement("SELECT id FROM dorm_room WHERE id=? FOR UPDATE")) {
                lock.setString(1, room);
                lock.executeQuery().close();
            }
            var future =
                    executor.submit(
                            () ->
                                    request(
                                            manager,
                                            "POST",
                                            "/hygiene-inspections",
                                            Map.of(
                                                    "roomId",
                                                    room,
                                                    "inspectionDate",
                                                    at.atZone(ZoneId.of("Asia/Shanghai"))
                                                            .toLocalDate()
                                                            .toString(),
                                                    "inspectedAt",
                                                    at.plusSeconds(10).toString(),
                                                    "round",
                                                    "RECHECK-" + round,
                                                    "relatedInspectionId",
                                                    original.path("id").asText(),
                                                    "floorScore",
                                                    20,
                                                    "deskScore",
                                                    20,
                                                    "bedScore",
                                                    20,
                                                    "bathroomScore",
                                                    20,
                                                    "imageIds",
                                                    List.of()),
                                            UUID.randomUUID().toString()));
            boolean blocked = false;
            try {
                future.get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException expected) {
                blocked = true;
            }
            // Isolated fixture mutation represents a concurrent void holding the same room lock.
            try (var update =
                    connection.prepareStatement(
                            "UPDATE hygiene_inspection SET voided_at=?,active_guard=NULL WHERE"
                                    + " id=?")) {
                update.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
                update.setString(2, original.path("id").asText());
                update.executeUpdate();
            }
            connection.commit();
            var response = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(blocked)
                    .as("a room mutation must serialize related inspection writes")
                    .isTrue();
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        }
    }

    @Test
    void failedDeliveryRecoversOnceWhenRecipientAccountIsRestored() throws Exception {
        String manager = token("manager1");
        String user = ok(manager, "GET", "/auth/me", null).path("id").asText();
        String source = "QA-recovery:" + UUID.randomUUID();
        try {
            db.update("UPDATE sys_user SET enabled=FALSE WHERE id=?", user);
            String id =
                    db.insert(
                            "INSERT INTO"
                                + " service_message(source_key,recipient_user_id,kind,title,body,business_type,business_id,created_at,next_attempt_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)",
                            source,
                            user,
                            "QA_RECOVERY",
                            "QA恢复提醒",
                            "fictional delivery recovery",
                            "REPAIR",
                            "1",
                            java.sql.Timestamp.from(Instant.now()),
                            java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
            for (int i = 0;
                    i < 12
                            && db.count("SELECT attempt_count FROM service_message WHERE id=?", id)
                                    < 5;
                    i++) {
                db.update(
                        "UPDATE service_message SET next_attempt_at=? WHERE id=?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                        id);
                messages.deliver();
            }
            assertThat(
                            db.one("SELECT delivery_state FROM service_message WHERE id=?", id)
                                    .get("deliveryState"))
                    .isEqualTo("FAILED");
            assertThat(db.count("SELECT attempt_count FROM service_message WHERE id=?", id))
                    .isEqualTo(5);
            db.update("UPDATE sys_user SET enabled=TRUE WHERE id=?", user);
            db.update(
                    "UPDATE service_message SET next_attempt_at=? WHERE id=?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    id);
            messages.deliver();
            var delivered =
                    db.one(
                            "SELECT delivery_state,delivered_at FROM service_message WHERE id=?",
                            id);
            assertThat(delivered.get("deliveryState")).isEqualTo("DELIVERED");
            messages.deliver();
            assertThat(
                            db.one("SELECT delivered_at FROM service_message WHERE id=?", id)
                                    .get("deliveredAt"))
                    .isEqualTo(delivered.get("deliveredAt"));
            assertThat(db.count("SELECT COUNT(*) FROM service_message WHERE source_key=?", source))
                    .isEqualTo(1);
            assertThat(
                            ok(
                                            manager,
                                            "POST",
                                            "/messages/" + id + "/read",
                                            Map.of("expectedVersion", 0))
                                    .path("title")
                                    .asText())
                    .isEqualTo("QA恢复提醒");
        } finally {
            db.update("UPDATE sys_user SET enabled=TRUE WHERE id=?", user);
        }
    }
}
