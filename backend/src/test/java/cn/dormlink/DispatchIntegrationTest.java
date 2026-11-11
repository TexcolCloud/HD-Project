package cn.dormlink;

import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.jdbc.SchemaMigrations;
import cn.dormlink.dispatch.DispatchRepository;
import cn.dormlink.dispatch.DispatchSignals;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:dispatch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
      "app.seed=true",
      "app.cookie-secure=false",
      "app.ai.api-key=",
      "app.dispatch.automatic=false",
      "app.files=target/dispatch-uploads"
    })
class DispatchIntegrationTest {
  @org.springframework.test.context.DynamicPropertySource
  static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
    OpenGaussTestDatabase.configure(registry, "dispatch");
  }

  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = new JsonMapper();
  @Autowired Db db;
  @Autowired DispatchRepository repository;
  @Autowired SchemaMigrations migrations;
  @Autowired DispatchSignals signals;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @BeforeEach
  void resetPresence() {
    db.update("UPDATE auth_session SET last_seen_at=NULL");
    db.update(
        "UPDATE dispatch_manager SET accepting_new_tasks=TRUE,available_until=?",
        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(28800)));
  }

  HttpResponse<String> call(String token, String method, String path, Object body)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
    else
      b.header("Content-Type", "application/json")
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  String login(String name) throws Exception {
    return json.readTree(
            call(
                    null,
                    "POST",
                    "/auth/login",
                    Map.of("username", name, "password", "Demo@123456", "clientType", "MOBILE"))
                .body())
        .path("data")
        .path("token")
        .asText();
  }

  tools.jackson.databind.JsonNode ok(String token, String method, String path, Object body)
      throws Exception {
    var r = call(token, method, path, body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return json.readTree(r.body()).path("data");
  }

  tools.jackson.databind.JsonNode repair(String token, String title, String priority)
      throws Exception {
    return ok(
        token,
        "POST",
        "/repairs",
        Map.of(
            "title",
            title,
            "description",
            "虚构调度回归故障",
            "category",
            "UTILITIES",
            "priority",
            priority,
            "urgentReason",
            priority.equals("URGENT") ? "持续漏水" : "",
            "contactName",
            "虚构学生",
            "contactPhone",
            "13800000000",
            "reportImageIds",
            List.of()));
  }

  @Test
  void heartbeatIsPerSessionAndDeduplicatesManagersAcrossTwoClients() throws Exception {
    String web = login("manager1"), mobile = login("manager1");
    assertThat(call(web, "POST", "/dispatch/heartbeat", Map.of()).statusCode()).isEqualTo(200);
    assertThat(call(mobile, "POST", "/dispatch/heartbeat", Map.of()).statusCode()).isEqualTo(200);
    var board = json.readTree(call(web, "GET", "/dispatch/board", null).body()).path("data");
    assertThat(board.path("onlineCount").asInt()).isEqualTo(1);
    assertThat(call(web, "POST", "/auth/logout", Map.of()).statusCode()).isEqualTo(200);
    assertThat(
            json.readTree(call(mobile, "GET", "/dispatch/board", null).body())
                .path("data")
                .path("onlineCount")
                .asInt())
        .isEqualTo(1);
    assertThat(call(login("student1"), "POST", "/dispatch/heartbeat", Map.of()).statusCode())
        .isEqualTo(403);
  }

  @Test
  void studentCanSubmitWindowPreferenceWhilePreservingUnchangedResponsibilityFields()
      throws Exception {
    String token = login("student1");
    var order =
        json.readTree(call(token, "GET", "/repairs", null).body())
            .path("data")
            .path("items")
            .path(0);
    var body =
        Map.of(
            "expectedVersion",
            order.path("version").asInt(),
            "windowStart",
            java.time.Instant.now().plusSeconds(3600).toString(),
            "windowEnd",
            java.time.Instant.now().plusSeconds(7200).toString(),
            "windowConfirmed",
            false,
            "estimatedMinutes",
            30,
            "visitRequired",
            true,
            "waitingReason",
            "");
    var response = call(token, "PATCH", "/repairs/" + order.path("id").asText() + "/visit", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("data").path("windowConfirmed").asBoolean())
        .isFalse();
  }

  @Test
  void pauseIsSharedByAccountAndExpirationUsesServerHeartbeatTime() throws Exception {
    String one = login("manager1"), two = login("manager1");
    var profile = ok(one, "POST", "/dispatch/heartbeat", Map.of());
    ok(two, "POST", "/dispatch/heartbeat", Map.of());
    ok(
        one,
        "PATCH",
        "/dispatch/me",
        Map.of("acceptingNewTasks", false, "expectedVersion", profile.path("version").asInt()));
    assertThat(
            ok(two, "POST", "/dispatch/heartbeat", Map.of()).path("acceptingNewTasks").asBoolean())
        .isFalse();
    assertThat(ok(two, "GET", "/dispatch/board", null).path("participatingCount").asInt()).isZero();
    db.update(
        "UPDATE auth_session SET last_seen_at=? WHERE user_id=(SELECT id FROM sys_user WHERE"
            + " username='manager1')",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(121)));
    assertThat(ok(two, "GET", "/dispatch/board", null).path("onlineCount").asInt()).isZero();
  }

  @Test
  void planningIsScopedClaimsAreUniqueAndLockedTravelDoesNotSkipRepairStates() throws Exception {
    String manager1 = login("manager1"),
        manager2 = login("manager2"),
        student1 = login("student1"),
        student2 = login("student2");
    var m1 = ok(manager1, "POST", "/dispatch/heartbeat", Map.of());
    var m2 = ok(manager2, "POST", "/dispatch/heartbeat", Map.of());
    var a = repair(student1, "A楼调度回归", "NORMAL");
    var b = repair(student2, "B楼调度回归", "NORMAL");
    var plan = ok(manager1, "POST", "/dispatch/replan", Map.of());
    assertThat(plan.path("onlineCount").asInt()).isEqualTo(2);
    assertThat(plan.path("dirty").asBoolean()).isFalse();
    var bBoard = ok(manager2, "GET", "/dispatch/board", null);
    String bBuilding = b.path("buildingId").asText();
    for (var route : bBoard.path("routes"))
      for (var task : route.path("tasks"))
        assertThat(task.path("buildingId").asText()).isEqualTo(bBuilding);
    for (var item : bBoard.path("unassigned"))
      assertThat(item.path("task").path("buildingId").asText()).isEqualTo(bBuilding);
    assertThat(
            ok(student2, "GET", "/repairs/" + b.path("id").asText(), null).path("status").asText())
        .isEqualTo("SUBMITTED");
    String orderId = b.path("id").asText();
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () ->
                  call(
                      manager1,
                      "POST",
                      "/dispatch/tasks/" + orderId + "/claim",
                      Map.of("expectedOrderVersion", 0)));
      var second =
          threads.submit(
              () ->
                  call(
                      manager2,
                      "POST",
                      "/dispatch/tasks/" + orderId + "/claim",
                      Map.of("expectedOrderVersion", 0)));
      assertThat(List.of(first.get().statusCode(), second.get().statusCode()))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(db.count("SELECT COUNT(*) FROM dispatch_task WHERE active_order_id=?", orderId))
        .isEqualTo(1);
    var claimed = ok(student2, "GET", "/repairs/" + orderId, null);
    String owner =
        claimed.path("handlerUserId").asText().equals(m1.path("userId").asText())
            ? manager1
            : manager2;
    var departed =
        ok(
            owner,
            "POST",
            "/dispatch/tasks/" + orderId + "/depart",
            Map.of("expectedTaskVersion", 0));
    assertThat(departed.path("state").asText()).isEqualTo("EN_ROUTE");
    repair(student1, "新增较急插单回归", "URGENT");
    var newer = ok(manager1, "POST", "/dispatch/replan", Map.of());
    boolean foundLocked = false;
    for (var route : newer.path("routes"))
      for (var task : route.path("tasks"))
        if (task.path("orderId").asText().equals(orderId)) {
          assertThat(task.path("locked").asBoolean()).isTrue();
          foundLocked = true;
        }
    assertThat(foundLocked).isTrue();
    ok(
        owner,
        "POST",
        "/dispatch/tasks/" + orderId + "/arrive",
        Map.of("expectedTaskVersion", departed.path("version").asInt()));
    assertThat(ok(student2, "GET", "/repairs/" + orderId, null).path("status").asText())
        .isEqualTo("ACCEPTED");
    var started =
        ok(
            owner,
            "POST",
            "/repairs/" + orderId + "/start",
            Map.of("expectedVersion", claimed.path("version").asInt()));
    ok(
        owner,
        "POST",
        "/repairs/" + orderId + "/finish",
        Map.of(
            "expectedVersion",
            started.path("version").asInt(),
            "resultDescription",
            "回归处理结果",
            "resultImageIds",
            List.of()));
    assertThat(db.count("SELECT COUNT(*) FROM dispatch_task WHERE active_order_id=?", orderId))
        .isZero();
    assertThat(ok(student2, "GET", "/repairs/" + orderId, null).path("status").asText())
        .isEqualTo("PENDING_CONFIRMATION");
  }

  @Test
  void staleCalculatedPlanCannotPublishOverANewerOrderEvenWithoutADirtyEvent() throws Exception {
    var snapshot = repository.snapshot(false);
    var result = new cn.dormlink.dispatch.RoutePlanner().plan(snapshot.input());
    var order = db.one("SELECT id FROM repair_order WHERE status='SUBMITTED' ORDER BY id LIMIT 1");
    db.update("UPDATE repair_order SET version=version+1 WHERE id=?", order.get("id"));
    assertThat(repository.publish(snapshot, result)).isFalse();
  }

  @Test
  void additiveMigrationReplaysWithoutLosingExistingBusinessRecords() throws Exception {
    long before = db.count("SELECT COUNT(*) FROM repair_order");
    migrations.afterPropertiesSet();
    assertThat(db.count("SELECT COUNT(*) FROM repair_order")).isEqualTo(before);
    assertThat(db.count("SELECT COUNT(*) FROM schema_migration WHERE version=2")).isEqualTo(1);
  }

  @Test
  void zeroActiveManagersKeepsNewWorkExplicitlyUnassigned() throws Exception {
    String student = login("student1"), manager = login("manager1");
    var order = repair(student, "无人在线调度回归", "NORMAL");
    var board = ok(manager, "POST", "/dispatch/replan", Map.of());
    assertThat(board.path("onlineCount").asInt()).isZero();
    assertThat(board.path("participatingCount").asInt()).isZero();
    boolean found = false;
    for (var item : board.path("unassigned"))
      if (item.path("task").path("orderId").asText().equals(order.path("id").asText())) {
        found = true;
        assertThat(item.path("reason").asText()).isNotBlank();
        assertThat(item.path("candidateCount").asInt()).isZero();
      }
    assertThat(found).isTrue();
  }

  @Test
  void oneManagerCannotStartOrDepartAnotherTaskUntilCurrentIsBlockedOrFinished() throws Exception {
    String manager = login("manager1"), student = login("student1");
    ok(manager, "POST", "/dispatch/heartbeat", Map.of());
    var one = repair(student, "执行互斥第一站", "NORMAL");
    var two = repair(student, "执行互斥第二站", "NORMAL");
    String first = one.path("id").asText(), second = two.path("id").asText();
    var acceptedOne =
        ok(manager, "POST", "/repairs/" + first + "/accept", Map.of("expectedVersion", 0));
    var acceptedTwo =
        ok(manager, "POST", "/repairs/" + second + "/accept", Map.of("expectedVersion", 0));
    ok(
        manager,
        "POST",
        "/repairs/" + first + "/start",
        Map.of("expectedVersion", acceptedOne.path("version").asInt()));
    assertThat(
            call(
                    manager,
                    "POST",
                    "/repairs/" + second + "/start",
                    Map.of("expectedVersion", acceptedTwo.path("version").asInt()))
                .statusCode())
        .isEqualTo(409);
    assertThat(ok(student, "GET", "/repairs/" + second, null).path("status").asText())
        .isEqualTo("ACCEPTED");
    assertThat(
            call(
                    manager,
                    "POST",
                    "/dispatch/tasks/" + second + "/depart",
                    Map.of("expectedTaskVersion", 0))
                .statusCode())
        .isEqualTo(409);
    int firstVersion =
        (int) db.count("SELECT version FROM dispatch_task WHERE active_order_id=?", first);
    ok(
        manager,
        "POST",
        "/dispatch/tasks/" + first + "/block",
        Map.of("expectedTaskVersion", firstVersion, "reason", "等待配件"));
    assertThat(
            ok(
                    manager,
                    "POST",
                    "/dispatch/tasks/" + second + "/depart",
                    Map.of("expectedTaskVersion", 0))
                .path("state")
                .asText())
        .isEqualTo("EN_ROUTE");
    assertThat(ok(student, "GET", "/repairs/" + first, null).path("status").asText())
        .isEqualTo("PROCESSING");
    var profile = ok(manager, "POST", "/dispatch/heartbeat", Map.of());
    assertThat(profile.path("positionBuildingId").asText())
        .isEqualTo(one.path("buildingId").asText());
    ok(
        manager,
        "POST",
        "/dispatch/tasks/" + second + "/block",
        Map.of("expectedTaskVersion", 1, "reason", "结束本次执行互斥回归"));
  }

  @Test
  void explicitHandoffPreservesAuditAndRejectsTargetWithoutBuildingScope() throws Exception {
    String manager = login("manager1"), other = login("manager2"), student = login("student2");
    ok(manager, "POST", "/dispatch/heartbeat", Map.of());
    var target = ok(other, "POST", "/dispatch/heartbeat", Map.of());
    var order = repair(student, "B楼交接回归", "NORMAL");
    String id = order.path("id").asText();
    var accepted = ok(manager, "POST", "/repairs/" + id + "/accept", Map.of("expectedVersion", 0));
    var handed =
        ok(
            manager,
            "POST",
            "/dispatch/tasks/" + id + "/handoff",
            Map.of(
                "targetManagerId",
                target.path("userId").asText(),
                "reason",
                "本次离岗交接",
                "expectedOrderVersion",
                accepted.path("version").asInt(),
                "expectedTaskVersion",
                0));
    assertThat(handed.path("handlerUserId").asText()).isEqualTo(target.path("userId").asText());
    assertThat(
            call(
                    manager,
                    "POST",
                    "/dispatch/tasks/" + id + "/depart",
                    Map.of("expectedTaskVersion", 1))
                .statusCode())
        .isEqualTo(403);
    assertThat(ok(student, "GET", "/repairs/" + id + "/events", null).toString())
        .contains("handoff");
    var a = repair(login("student1"), "A楼不可交接回归", "NORMAL");
    var aAccepted =
        ok(
            manager,
            "POST",
            "/repairs/" + a.path("id").asText() + "/accept",
            Map.of("expectedVersion", 0));
    assertThat(
            call(
                    manager,
                    "POST",
                    "/dispatch/tasks/" + a.path("id").asText() + "/handoff",
                    Map.of(
                        "targetManagerId",
                        target.path("userId").asText(),
                        "reason",
                        "无权限交接测试",
                        "expectedOrderVersion",
                        aAccepted.path("version").asInt(),
                        "expectedTaskVersion",
                        0))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void workPeriodExpiryDuringCalculationInvalidatesPublication() throws Exception {
    String manager = login("manager1");
    ok(manager, "POST", "/dispatch/heartbeat", Map.of());
    db.update(
        "UPDATE dispatch_manager SET available_until=? WHERE user_id=(SELECT id FROM sys_user WHERE"
            + " username='manager1')",
        java.sql.Timestamp.from(java.time.Instant.now().plusMillis(1000)));
    var snapshot = repository.snapshot(false);
    assertThat(
            snapshot.input().workers().stream()
                .anyMatch(
                    w ->
                        w.id()
                                .equals(
                                    db.one("SELECT id FROM sys_user WHERE username='manager1'")
                                        .get("id"))
                            && w.acceptingNew()))
        .isTrue();
    var result = new cn.dormlink.dispatch.RoutePlanner().plan(snapshot.input());
    Thread.sleep(1100);
    assertThat(repository.publish(snapshot, result)).isFalse();
  }

  @Test
  void rolledBackBusinessDoesNotEmitACommittedDirtySignal() {
    signals.flush();
    assertThat(signals.pending()).isFalse();
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              signals.changed(null, null, "ROLLED_BACK_TEST");
              status.setRollbackOnly();
            });
    assertThat(signals.pending()).isFalse();
    assertThat(db.count("SELECT COUNT(*) FROM dispatch_event WHERE event_type='ROLLED_BACK_TEST'"))
        .isZero();
  }
}
