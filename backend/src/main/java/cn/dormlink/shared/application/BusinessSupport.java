package cn.dormlink.shared.application;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.api.Inputs;

import cn.dormlink.shared.api.ApiException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BusinessSupport {
  final Db db;
  final AuthService auth;
  final JsonMapper json;

  BusinessSupport(Db db, AuthService auth, JsonMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  public void building(Object id) {
    var a = auth.current();
    a.requireManager();
    if (db.count(
            "SELECT COUNT(*) FROM manager_building WHERE manager_user_id=? AND building_id=?",
            a.id(),
            id)
        == 0) throw ApiException.missing();
  }

  public String scope(String column) {
    var a = auth.current();
    a.requireManager();
    return " EXISTS(SELECT 1 FROM manager_building mb WHERE mb.manager_user_id="
        + Long.parseLong(a.id())
        + " AND mb.building_id="
        + column
        + ") ";
  }

  public Map<String, Object> page(String sql, List<Object> args, Map<String, String> query) {
    int page = positive(query.get("page"), 1, 100000),
        size = positive(query.get("pageSize"), 20, 100);
    long count = db.count("SELECT COUNT(*) FROM (" + sql + ") page_count", args.toArray());
    var params = new ArrayList<>(args);
    params.add(size);
    params.add((page - 1) * size);
    var items = db.rows(sql + " LIMIT ? OFFSET ?", params.toArray());
    return new LinkedHashMap<>(
        Map.of("items", items, "total", count, "page", page, "pageSize", size));
  }

  int positive(String s, int fallback, int max) {
    if (s == null || s.isBlank()) return fallback;
    try {
      int n = Integer.parseInt(s);
      if (n < 1 || n > max) throw new Exception();
      return n;
    } catch (Exception e) {
      throw ApiException.bad("分页参数无效");
    }
  }

  public void version(Map<String, Object> row, Map<String, Object> b) {
    if (Db.number(row, "version") != Inputs.version(b))
      throw new ApiException(409, "VERSION_CONFLICT", "记录已更新，请刷新后重试");
  }

  public void changed(int affected) {
    if (affected != 1) throw new ApiException(409, "VERSION_CONFLICT", "记录已更新，请刷新后重试");
  }

  public void audit(String action, String type, Object id, String note, Object changes) {
    var a = auth.current();
    db.insert(
        "INSERT INTO"
            + " audit_event(operator_id,operator_name,action,business_type,business_id,note,changes,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        a.id(),
        a.name(),
        action,
        type,
        id,
        note,
        json.writeValueAsString(changes),
        Timestamp.from(Instant.now()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Object command(String route, String key, Map<String, Object> body, Supplier<Object> work) {
    var a = auth.current();
    // ponytail: serialize one user's writes for SQL-only idempotency; split by key if measured
    // per-user write throughput becomes a bottleneck. Room/order locks protect different users.
    db.one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", a.id());
    String k = key == null || key.isBlank() ? UUID.randomUUID().toString() : key;
    if (k.length() > 64) throw ApiException.bad("Idempotency-Key长度不能超过64");
    String hash = AuthService.hash(json.writeValueAsString(new TreeMap<>(body)));
    String permission = permissionFingerprint(a);
    var old =
        db.find(
            "SELECT request_hash,response_data,permission_hash FROM idempotency_record WHERE user_id=? AND"
                + " route_key=? AND request_key=?",
            a.id(),
            route,
            k);
    if (old != null) {
      if (!old.get("requestHash").equals(hash))
        throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "同一提交标识不能对应不同内容");
      if (!permission.equals(old.get("permissionHash"))) {
        throw new ApiException(403, "FORBIDDEN", "权限已变化，请刷新后重新提交");
      }
      return json.readValue(old.get("responseData").toString(), Object.class);
    }
    Object result = work.get();
    db.insert(
        "INSERT INTO"
            + " idempotency_record(user_id,route_key,request_key,request_hash,response_data,created_at,permission_hash)"
            + " VALUES(?,?,?,?,?,?,?)",
        a.id(),
        route,
        k,
        hash,
        json.writeValueAsString(result),
        Timestamp.from(Instant.now()), permission);
    return result;
  }

  private String permissionFingerprint(cn.dormlink.identity.Actor actor) {
    var user = db.one("SELECT role,enabled FROM sys_user WHERE id=?", actor.id());
    var buildings = db.rows("SELECT building_id FROM manager_building WHERE manager_user_id=? ORDER BY building_id", actor.id());
    var student = db.find("SELECT id,status FROM student WHERE user_id=?", actor.id());
    return AuthService.hash(json.writeValueAsString(Arrays.asList(user, buildings, student)));
  }

  public static String filter(
      String sql, List<Object> args, Map<String, String> q, String key, String column) {
    String v = q.get(key);
    if (v != null && !v.isBlank()) {
      args.add(v);
      return sql + " AND " + column + "=?";
    }
    return sql;
  }

  public static String search(String sql, List<Object> args, Map<String, String> q, String... columns) {
    String term = q.get("q");
    if (term == null || term.isBlank()) return sql;
    if (term.length() > 100) throw ApiException.bad("搜索词过长");
    sql += " AND (";
    for (int i = 0; i < columns.length; i++) {
      if (i > 0) sql += " OR ";
      sql += columns[i] + " LIKE ?";
      args.add("%" + term + "%");
    }
    return sql + ")";
  }

  public static String dates(String sql, List<Object> args, Map<String, String> q, String col) {
    if (q.containsKey("startDate") && !q.get("startDate").isBlank()) {
      args.add(Inputs.time(Map.of("date", q.get("startDate")), "date", Instant.now()));
      sql += " AND " + col + ">=?";
    }
    if (q.containsKey("endDate") && !q.get("endDate").isBlank()) {
      var end = Inputs.time(Map.of("date", q.get("endDate")), "date", Instant.now());
      args.add(Timestamp.from(end.toInstant().plusSeconds(86400)));
      sql += " AND " + col + "<?";
    }
    return sql;
  }
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> items(Map<String, Object> page) {
    return (List<Map<String, Object>>) page.get("items");
  }
}
