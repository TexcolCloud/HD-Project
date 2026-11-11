package cn.dormlink.dispatch;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;

import cn.dormlink.shared.api.ApiException;

import cn.dormlink.dispatch.RoutePlanner;
import cn.dormlink.mobility.MobilityService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DispatchRepository {
  final Db db;
  final DispatchPresence presence;
  final DispatchSignals signals;
  final JsonMapper json;
  final TransactionTemplate transaction;
  final TransactionTemplate reads;
  private final MobilityService mobility;

  DispatchRepository(
      Db db,
      DispatchPresence presence,
      DispatchSignals signals,
      JsonMapper json,
      PlatformTransactionManager tx, MobilityService mobility) {
    this.db = db;
    this.presence = presence;
    this.signals = signals;
    this.json = json;
    this.mobility = mobility;
    transaction = new TransactionTemplate(tx);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    reads = new TransactionTemplate(tx);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  void initialize() {
    transaction.executeWithoutResult(
        s -> {
          signals.ensureRevision();
          for (var u :
              db.rows("SELECT id FROM sys_user WHERE role='MANAGER' AND enabled=TRUE ORDER BY id"))
            presence.ensureManager(u.get("id").toString());
          for (var o :
              db.rows(
                  "SELECT * FROM repair_order WHERE status IN ('ACCEPTED','PROCESSING') AND"
                      + " handler_user_id IS NOT NULL ORDER BY id"))
            ensureClaim(o, o.get("status").equals("PROCESSING") ? "ON_SITE" : "ASSIGNED");
          var a = db.find("SELECT id FROM building WHERE code='A'");
          var b = db.find("SELECT id FROM building WHERE code='B'");
          if (a != null && b != null) {
            defaultLink(a.get("id"), b.get("id"));
            defaultLink(b.get("id"), a.get("id"));
          }
        });
  }

  void defaultLink(Object from, Object to) {
    if (db.count(
            "SELECT COUNT(*) FROM dispatch_travel WHERE from_building_id=? AND to_building_id=?",
            from,
            to)
        == 0)
      db.update(
          "INSERT INTO dispatch_travel(from_building_id,to_building_id,minutes) VALUES(?,?,5)",
          from,
          to);
  }

  public Map<String, Object> revision() {
    return db.one("SELECT * FROM dispatch_revision WHERE id=1");
  }

  public <T> T readPlan(Supplier<T> work) {
    return reads.execute(status -> work.get());
  }

  public Map<String, Object> targetManager(String id) {
    return presence.manager(id);
  }

  String latestResult() {
    var p = db.find("SELECT result_json FROM dispatch_plan ORDER BY revision DESC LIMIT 1");
    return p == null ? null : p.get("resultJson").toString();
  }

  RoutePlanner.Result resultAt(long revision) {
    var row = db.find("SELECT result_json FROM dispatch_plan WHERE revision=?", revision);
    return row == null ? null : result(row.get("resultJson").toString());
  }

  RoutePlanner.Result result(String value) {
    return value == null ? null : json.readValue(value, RoutePlanner.Result.class);
  }

  List<Map<String, Object>> orders(boolean lock) {
    return db.rows(
        "SELECT o.*,r.floor_no,t.id claim_id,t.manager_user_id task_manager_id,t.state"
            + " task_state,t.reason task_reason,t.version task_version FROM repair_order o JOIN"
            + " dorm_room r ON r.id=o.room_id LEFT JOIN dispatch_task t ON t.active_order_id=o.id"
            + " WHERE o.status IN ('SUBMITTED','ACCEPTED','PROCESSING') ORDER BY CASE WHEN"
            + " o.status='PROCESSING' THEN 0 WHEN o.status='ACCEPTED' THEN 1 ELSE 2"
            + " END,o.submitted_at,o.id"
            + (lock ? " FOR UPDATE" : ""));
  }

  List<Map<String, Object>> managers() {
    return presence.managers();
  }

  public String eligibilityFingerprint() {
    return json.writeValueAsString(Map.of("managers", planningManagers(managers(), Instant.now()),
        "travel", new TreeMap<>(effectiveTravel())));
  }

  private Map<String, Integer> effectiveTravel() {
    var baseline = new HashMap<String, Integer>();
    for (var link : db.rows("SELECT from_building_id,to_building_id,minutes FROM dispatch_travel ORDER BY from_building_id,to_building_id")) {
      baseline.put(link.get("fromBuildingId") + ":" + link.get("toBuildingId"), (int) Db.number(link, "minutes"));
    }
    return mobility.travelMinutes(baseline);
  }

  private List<Map<String, Object>> planningManagers(List<Map<String, Object>> people, Instant now) {
    var states = new ArrayList<Map<String, Object>>();
    for (var person : people) {
      var state = new LinkedHashMap<String, Object>();
      for (String field : List.of("userId", "buildingIds", "acceptingNewTasks", "availableUntil", "positionBuildingId", "positionFloorNo", "version")) {
        state.put(field, person.get(field));
      }
      state.put("participating", participating(person, now));
      states.add(state);
    }
    return states;
  }

  public boolean participating(Map<String, Object> m, Instant now) {
    return Boolean.TRUE.equals(m.get("online"))
        && Boolean.TRUE.equals(m.get("acceptingNewTasks"))
        && m.get("availableUntil") != null
        && Instant.parse(m.get("availableUntil").toString()).isAfter(now);
  }

  @SuppressWarnings("unchecked")
  public static List<String> buildingIds(Map<String, Object> m) {
    return ((List<Object>) m.get("buildingIds")).stream().map(Object::toString).toList();
  }

  static Instant instant(Object value) {
    return value == null ? null : Instant.parse(value.toString());
  }

  public DispatchSnapshot snapshot(boolean lock) {
    if (lock) {
      db.rows(
          "SELECT id FROM repair_order WHERE status IN ('SUBMITTED','ACCEPTED','PROCESSING') ORDER"
              + " BY id FOR UPDATE");
      db.rows(
          "SELECT id FROM dispatch_task WHERE active_order_id IS NOT NULL ORDER BY id FOR UPDATE");
      db.rows("SELECT user_id FROM dispatch_manager ORDER BY user_id FOR UPDATE");
      db.rows(
          "SELECT id FROM auth_session WHERE user_id IN(SELECT id FROM sys_user WHERE"
              + " role='MANAGER') ORDER BY id FOR UPDATE");
      db.rows(
          "SELECT manager_user_id,building_id FROM manager_building ORDER BY"
              + " manager_user_id,building_id FOR UPDATE");
      db.rows(
          "SELECT from_building_id,to_building_id FROM dispatch_travel ORDER BY"
              + " from_building_id,to_building_id FOR UPDATE");
    }
    var rev = revision();
    var people = managers();
    var work = orders(false);
    var links = db.rows("SELECT * FROM dispatch_travel ORDER BY from_building_id,to_building_id");
    Instant now = Instant.now();
    Map<String, List<String>> previous = new HashMap<>();
    var old = resultAt(Db.number(rev, "revision"));
    Map<String, String> previousOwner = new HashMap<>();
    Map<String, Integer> previousPosition = new HashMap<>();
    if (old != null)
      for (var route : old.routes()) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < route.stops().size(); i++) {
          String id = route.stops().get(i).orderId();
          ids.add(id);
          previousOwner.put(id, route.managerId());
          previousPosition.put(id, i);
        }
        previous.put(route.managerId(), ids);
      }
    var workers = new ArrayList<RoutePlanner.Worker>();
    for (var manager : people) {
      String id = manager.get("userId").toString();
      var locked = new ArrayList<String>();
      for (var order : work)
        if (id.equals(order.get("handlerUserId"))
            && Set.of("ON_SITE", "EN_ROUTE").contains(Objects.toString(order.get("taskState"), "")))
          locked.add(order.get("id").toString());
      locked.sort(
          Comparator.<String>comparingInt(
                  orderId -> {
                    var order =
                        work.stream()
                            .filter(r -> orderId.equals(r.get("id")))
                            .findFirst()
                            .orElseThrow();
                    return "ON_SITE".equals(order.get("taskState")) ? 0 : 1;
                  })
              .thenComparingInt(
                  orderId -> previousPosition.getOrDefault(orderId, Integer.MAX_VALUE)));
      workers.add(
          new RoutePlanner.Worker(
              id,
              new HashSet<>(buildingIds(manager)),
              participating(manager, now),
              instant(manager.get("availableUntil")),
              new RoutePlanner.Node(
                  Db.number(manager, "positionFloorNo") > 0
                      ? Objects.toString(manager.get("positionBuildingId"), null) : null,
                  (int) Db.number(manager, "positionFloorNo")),
              List.copyOf(locked)));
    }
    var jobs = new ArrayList<RoutePlanner.Job>();
    var excluded = new ArrayList<Map<String, Object>>();
    for (var order : work) {
      String code = null, reason = null;
      if (!Boolean.TRUE.equals(order.get("visitRequired"))) {
        code = "VISIT_NOT_REQUIRED";
        reason = Objects.toString(order.get("waitingReason"), "无需上门，保留办理责任");
      } else if ("BLOCKED".equals(order.get("taskState"))) {
        code = "BLOCKED";
        reason = Objects.toString(order.get("taskReason"), "等待条件恢复");
      } else if (!Objects.toString(order.get("waitingReason"), "").isBlank()) {
        code = "WAITING_CONDITION";
        reason = order.get("waitingReason").toString();
      } else if (jobs.size() >= 500) {
        code = "BATCH_LIMIT";
        reason = "本批最多规划500个任务，等待下一批安排";
      }
      if (code != null) {
        var skipped = new LinkedHashMap<>(order);
        skipped.put("reasonCode", code);
        skipped.put("reason", reason);
        excluded.add(skipped);
        continue;
      }
      String id = order.get("id").toString();
      boolean locked =
          Set.of("ON_SITE", "EN_ROUTE").contains(Objects.toString(order.get("taskState"), ""));
      jobs.add(
          new RoutePlanner.Job(
              id,
              new RoutePlanner.Node(
                  order.get("buildingId").toString(), (int) Db.number(order, "floorNo")),
              order.get("priority").toString(),
              instant(order.get("submittedAt")),
              Math.max(1, (int) Db.number(order, "estimatedMinutes")),
              instant(order.get("windowStart")),
              instant(order.get("windowEnd")),
              Boolean.TRUE.equals(order.get("windowConfirmed")),
              Objects.toString(order.get("handlerUserId"), null),
              locked,
              previousOwner.get(id),
              previousPosition.getOrDefault(id, Integer.MAX_VALUE)));
    }
    Map<String, Integer> distances = new HashMap<>();
    for (var link : links)
      distances.put(
          link.get("fromBuildingId") + ":" + link.get("toBuildingId"),
          (int) Db.number(link, "minutes"));
    distances = new HashMap<>(mobility.travelMinutes(distances));
    var eligibility = planningManagers(people, now);
    var material = Map.of("managers", eligibility, "orders", work, "travel", distances);
    return new DispatchSnapshot(
        Db.number(rev, "revision"),
        Db.number(rev, "dataRevision"),
        AuthService.hash(json.writeValueAsString(material)),
        new RoutePlanner.Snapshot(now, workers, jobs, distances, 2, 1),
        people,
        work,
        excluded);
  }

  public boolean publish(DispatchSnapshot original, RoutePlanner.Result result) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              var rev = db.one("SELECT * FROM dispatch_revision WHERE id=1 FOR UPDATE");
              if (Db.number(rev, "revision") != original.revision()
                  || Db.number(rev, "dataRevision") != original.dataRevision()) return false;
              var current = snapshot(true);
              if (!current.fingerprint().equals(original.fingerprint())) return false;
              long next = original.revision() + 1;
              Timestamp now = Timestamp.from(Instant.now());
              db.update(
                  "INSERT INTO"
                      + " dispatch_plan(revision,data_revision,fingerprint,result_json,reason,created_at)"
                      + " VALUES(?,?,?,?,?,?)",
                  next,
                  original.dataRevision(),
                  original.fingerprint(),
                  json.writeValueAsString(result),
                  rev.get("reason"),
                  now);
              db.update(
                  "UPDATE dispatch_revision SET revision=?,dirty=FALSE,generated_at=?,updated_at=?"
                      + " WHERE id=1",
                  next,
                  now,
                  now);
              return true;
            }));
  }

  public Map<String, Object> activeClaim(String orderId) {
    return db.find("SELECT * FROM dispatch_task WHERE active_order_id=?", orderId);
  }

  Map<String, Object> ensureClaim(Map<String, Object> order, String state) {
    String id = order.get("id").toString();
    var task = activeClaim(id);
    if (task != null) {
      if (!Objects.equals(task.get("managerUserId"), order.get("handlerUserId")))
        throw ApiException.conflict("工单办理人与有效任务不一致");
      return task;
    }
    db.insert(
        "INSERT INTO"
            + " dispatch_task(order_id,active_order_id,manager_user_id,state,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        id,
        order.get("handlerUserId"),
        state,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
    return activeClaim(id);
  }

  public void repairChanged(String orderId, String action, String actorId) {
    var order = db.one("SELECT * FROM repair_order WHERE id=?", orderId);
    switch (action) {
      case "accept", "rework" -> ensureClaim(order, "ASSIGNED");
      case "start" -> {
        if (db.count(
                "SELECT COUNT(*) FROM dispatch_task WHERE manager_user_id=? AND active_order_id IS"
                    + " NOT NULL AND order_id<>? AND state IN ('ON_SITE','EN_ROUTE')",
                order.get("handlerUserId"),
                orderId)
            > 0) throw ApiException.conflict("请先完成或标记受阻的当前任务，不能同时开始另一站");
        ensureClaim(order, "ON_SITE");
        db.update(
            "UPDATE dispatch_task SET"
                + " state='ON_SITE',next_user_id=NULL,version=version+1,updated_at=? WHERE"
                + " active_order_id=?",
            Timestamp.from(Instant.now()),
            orderId);
        var room = db.one("SELECT floor_no FROM dorm_room WHERE id=?", order.get("roomId"));
        db.update(
            "UPDATE dispatch_manager SET"
                + " position_building_id=?,position_floor_no=?,position_updated_at=?,version=version+1"
                + " WHERE user_id=?",
            order.get("buildingId"),
            room.get("floorNo"),
            Timestamp.from(Instant.now()),
            order.get("handlerUserId"));
      }
      case "finish", "confirm", "cancel", "reject" ->
          db.update(
              "UPDATE dispatch_task SET"
                  + " state='DONE',active_order_id=NULL,next_user_id=NULL,version=version+1,updated_at=?"
                  + " WHERE active_order_id=?",
              Timestamp.from(Instant.now()),
              orderId);
      default -> {}
    }
    signals.changed(orderId, actorId, "REPAIR_" + action.toUpperCase(Locale.ROOT));
  }
}
