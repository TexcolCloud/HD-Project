package cn.dormlink;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

record AiAdmission(String requestId, String invocationId, Map<String, Object> cached) {}

/** Short SQL-only admission and immutable terminal result caching; never holds a lock over HTTP. */
@Component
class AiRequestLedger {
  private final Db db;
  private final AuthService auth;
  private final JsonMapper json;
  private final TransactionTemplate independent;

  AiRequestLedger(
      Db db, AuthService auth, JsonMapper json, PlatformTransactionManager transactions) {
    this.db = db;
    this.auth = auth;
    this.json = json;
    independent = new TransactionTemplate(transactions);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  AiCommand identify(AiCommand command) {
    String key = command.key();
    if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
    if (key.length() > 64 || command.route().length() > 150) throw ApiException.bad("AI请求标识长度无效");
    return new AiCommand(command.route(), key, command.body());
  }

  String requestHash(AiCommand command) {
    return AuthService.hash(json.writeValueAsString(canonical(command.body())));
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      for (var entry : map.entrySet())
        sorted.put(entry.getKey().toString(), canonical(entry.getValue()));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
    return value;
  }

  private String scopeHash() {
    var actor = auth.current();
    var scope =
        db.rows(
            "SELECT building_id FROM manager_building WHERE manager_user_id=? ORDER BY building_id",
            actor.id());
    return AuthService.hash(
        json.writeValueAsString(Map.of("role", actor.role(), "buildings", scope)));
  }

  private void lockUser() {
    db.one("SELECT id FROM sys_user WHERE id=? AND enabled=TRUE FOR UPDATE", auth.current().id());
  }

  private Map<String, Object> existing(AiCommand command) {
    return db.find(
        "SELECT * FROM idempotency_record WHERE user_id=? AND route_key=? AND request_key=?",
        auth.current().id(),
        command.route(),
        command.key());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> replay(Map<String, Object> row, AiCommand command) {
    if (!requestHash(command).equals(row.get("requestHash")))
      throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "同一AI请求标识不能用于不同内容");
    Map<String, Object> state;
    try {
      state = json.readValue(row.get("responseData").toString(), Map.class);
    } catch (RuntimeException e) {
      throw unknown();
    }
    if (!scopeHash().equals(state.get("scopeHash")))
      throw new ApiException(409, "AI_REQUEST_FAILED", "当前权限范围已变化，请明确发起新的AI请求");
    switch (Objects.toString(state.get("state"), "")) {
      case "SUCCESS":
        if (state.get("result") instanceof Map<?, ?> result)
          return new LinkedHashMap<>((Map<String, Object>) result);
        throw unknown();
      case "FAILED":
        throw new ApiException(409, "AI_REQUEST_FAILED", "上次AI请求已经失败；本次未重复调用，需重新生成时请发起新请求");
      case "UNKNOWN":
        throw unknown();
      case "RUNNING":
        if (Instant.parse(row.get("createdAt").toString()).isBefore(Instant.now().minusSeconds(90)))
          throw unknown();
        throw new ApiException(409, "AI_REQUEST_RUNNING", "同一AI请求仍在处理中，请保留当前请求标识稍后查询");
      default:
        throw unknown();
    }
  }

  private ApiException unknown() {
    return new ApiException(409, "AI_RESULT_UNKNOWN", "上次AI结果尚未确认，本次未重复调用；如需重新生成请明确发起新请求");
  }

  Map<String, Object> cached(AiCommand command) {
    return independent.execute(
        status -> {
          lockUser();
          var old = existing(command);
          return old == null ? null : replay(old, command);
        });
  }

  AiAdmission admit(AiCommand command, String model, int dailyLimit) {
    return independent.execute(
        status -> {
          lockUser();
          var old = existing(command);
          if (old != null) return new AiAdmission(null, null, replay(old, command));
          String user = auth.current().id();
          Instant day =
              LocalDate.now(ZoneId.of("Asia/Shanghai"))
                  .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                  .toInstant();
          if (db.count(
                  "SELECT COUNT(*) FROM ai_invocation WHERE user_id=? AND created_at>=?",
                  user,
                  Timestamp.from(day))
              >= dailyLimit) throw new ApiException(429, "AI_DAILY_LIMIT", "今日AI次数已用完，可继续人工办理");
          if (db.count(
                  "SELECT COUNT(*) FROM ai_invocation WHERE user_id=? AND created_at>=?",
                  user,
                  Timestamp.from(Instant.now().minusSeconds(60)))
              >= 2) throw new ApiException(429, "AI_RATE_LIMIT", "AI请求较频繁，请稍后重试");
          String call =
              db.insert(
                  "INSERT INTO ai_invocation(user_id,model,status,latency_ms,created_at)"
                      + " VALUES(?,?,?,?,?)",
                  user,
                  model,
                  "RUNNING",
                  0,
                  Timestamp.from(Instant.now()));
          String request =
              db.insert(
                  "INSERT INTO"
                      + " idempotency_record(user_id,route_key,request_key,request_hash,response_data,created_at)"
                      + " VALUES(?,?,?,?,?,?)",
                  user,
                  command.route(),
                  command.key(),
                  requestHash(command),
                  json.writeValueAsString(
                      Map.of("state", "RUNNING", "scopeHash", scopeHash(), "invocationId", call)),
                  Timestamp.from(Instant.now()));
          return new AiAdmission(request, call, null);
        });
  }

  void success(
      AiAdmission admission,
      Map<String, Object> result,
      long elapsed,
      Integer input,
      Integer output) {
    independent.executeWithoutResult(
        status -> {
          var old =
              db.one(
                  "SELECT response_data FROM idempotency_record WHERE id=? FOR UPDATE",
                  admission.requestId());
          @SuppressWarnings("unchecked")
          var state = json.readValue(old.get("responseData").toString(), Map.class);
          db.update(
              "UPDATE ai_invocation SET"
                  + " status='SUCCESS',latency_ms=?,input_tokens=?,output_tokens=? WHERE id=?",
              elapsed,
              input,
              output,
              admission.invocationId());
          db.update(
              "UPDATE idempotency_record SET response_data=? WHERE id=?",
              json.writeValueAsString(
                  Map.of(
                      "state", "SUCCESS", "scopeHash", state.get("scopeHash"), "result", result)),
              admission.requestId());
        });
  }

  void failure(AiAdmission admission, long elapsed) {
    terminal(admission, elapsed, "FAILED");
  }

  void unknown(AiAdmission admission, long elapsed) {
    terminal(admission, elapsed, "UNKNOWN");
  }

  private void terminal(AiAdmission admission, long elapsed, String outcome) {
    independent.executeWithoutResult(
        status -> {
          var old =
              db.one(
                  "SELECT response_data FROM idempotency_record WHERE id=? FOR UPDATE",
                  admission.requestId());
          @SuppressWarnings("unchecked")
          var state = json.readValue(old.get("responseData").toString(), Map.class);
          if ("SUCCESS".equals(state.get("state"))) return;
          db.update(
              "UPDATE ai_invocation SET status=?,latency_ms=? WHERE id=?",
              outcome,
              elapsed,
              admission.invocationId());
          db.update(
              "UPDATE idempotency_record SET response_data=? WHERE id=?",
              json.writeValueAsString(
                  Map.of("state", outcome, "scopeHash", state.get("scopeHash"))),
              admission.requestId());
        });
  }
}
