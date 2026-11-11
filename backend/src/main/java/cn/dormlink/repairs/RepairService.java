package cn.dormlink.repairs;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.media.FileService;
import cn.dormlink.dispatch.DispatchRepository;

import cn.dormlink.shared.api.ApiException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RepairService {
  final Db db;
  final AuthService auth;
  final BusinessSupport ops;
  final FileService files;
  final DispatchRepository dispatch;

  RepairService(
      Db d, AuthService a, BusinessSupport o, FileService f, DispatchRepository dispatch) {
    db = d;
    auth = a;
    ops = o;
    files = f;
    this.dispatch = dispatch;
  }

  public Map<String, Object> raw(String id) {
    var r = db.one("SELECT * FROM repair_order WHERE id=?", id);
    var a = auth.current();
    if (a.manager()) ops.building(r.get("buildingId"));
    else if (!Objects.equals(a.studentId(), r.get("reporterStudentId")))
      throw ApiException.missing();
    return r;
  }

  Map<String, Object> decorate(Map<String, Object> r) {
    decorateBatch(List.of(r));
    return r;
  }

  void decorateBatch(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return;
    var ids = rows.stream().map(row -> row.get("id").toString()).toList();
    var assets = files.assetsByBusiness("REPAIR", ids);
    var results = new HashMap<String, Object>();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    for (var event : db.rows("SELECT e.order_id,e.note FROM repair_event e WHERE e.order_id IN ("
        + placeholders + ") AND e.action='finish' AND NOT EXISTS (SELECT 1 FROM repair_event newer"
        + " WHERE newer.order_id=e.order_id AND newer.action='finish' AND newer.id>e.id)", ids.toArray())) {
      results.put(event.get("orderId").toString(), event.get("note"));
    }
    for (var row : rows) {
      String id = row.get("id").toString();
      var images = assets.getOrDefault(id, List.of());
      row.put("allowedActions", allowed(row));
      row.put("reportImages", images.stream().filter(image -> "REPORT".equals(image.get("usage"))).toList());
      row.put("resultImages", images.stream().filter(image -> "RESULT".equals(image.get("usage"))).toList());
      row.put("latestResult", results.get(id));
    }
  }

  List<String> allowed(Map<String, Object> r) {
    var a = auth.current();
    String status = r.get("status").toString();
    boolean handler = a.id().equals(r.get("handlerUserId"));
    if (a.manager()) {
      if (status.equals("SUBMITTED")) return List.of("accept", "reject");
      if (handler && status.equals("ACCEPTED")) return List.of("start", "notes");
      if (handler && status.equals("PROCESSING")) return List.of("finish", "notes");
      if (handler && status.equals("PENDING_CONFIRMATION")) return List.of("rework");
      return List.of();
    }
    if (status.equals("SUBMITTED")) return List.of("cancel");
    if (status.equals("PENDING_CONFIRMATION")) return List.of("confirm", "rework");
    return List.of();
  }

  public Object list(Map<String, String> q) {
    var a = auth.current();
    var args = new ArrayList<Object>();
    String sql = "SELECT o.* FROM repair_order o WHERE ";
    if (a.manager()) sql += ops.scope("o.building_id");
    else {
      sql += "o.reporter_student_id=?";
      args.add(a.studentId());
    }
    for (String key : List.of("buildingId", "roomId", "status", "category"))
      sql =
          BusinessSupport.filter(
              sql,
              args,
              q,
              key,
              "o."
                  + switch (key) {
                    case "buildingId" -> "building_id";
                    case "roomId" -> "room_id";
                    default -> key;
                  });
    sql =
        BusinessSupport.search(
            sql, args, q, "o.title", "o.order_no", "o.room_no", "o.reporter_name");
    sql = BusinessSupport.dates(sql, args, q, "o.submitted_at");
    var page = ops.page(sql + " ORDER BY o.id DESC", args, q);
    decorateBatch(BusinessSupport.items(page));
    return page;
  }

  Object detail(String id) {
    return decorate(raw(id));
  }

  Object events(String id) {
    raw(id);
    return db.rows(
        "SELECT id,order_id,from_status,to_status,action,operator_name,note,created_at FROM"
            + " repair_event WHERE order_id=? ORDER BY id",
        id);
  }

  Object create(Map<String, Object> b) {
    var a = auth.current();
    a.requireStudent();
    var h =
        db.find(
            "SELECT h.*,s.name student_name FROM residence h JOIN student s ON s.id=h.student_id"
                + " WHERE h.student_id=? AND h.checked_out_at IS NULL AND s.status='ACTIVE'",
            a.studentId());
    if (h == null) throw ApiException.bad("当前未入住，请联系宿管办理入住后报修");
    String title = Inputs.text(b, "title", 80, true),
        description = Inputs.text(b, "description", 1000, true),
        category = Inputs.choice(b, "category", "UTILITIES", "FURNITURE", "NETWORK", "OTHER"),
        priority = Inputs.choice(b, "priority", "NORMAL", "URGENT"),
        urgent = Inputs.text(b, "urgentReason", 200, priority.equals("URGENT")),
        name = Inputs.text(b, "contactName", 50, true),
        phone = Inputs.phone(b, "contactPhone"),
        slot = Inputs.text(b, "contactTimeSlot", 100, false);
    Long related = Inputs.optionalId(b, "relatedOrderId");
    if (related != null) {
      var old = raw(related.toString());
      if (!Set.of("COMPLETED", "CANCELLED", "REJECTED").contains(old.get("status")))
        throw ApiException.bad("只能关联已经结束的工单");
    }
    String number =
        "BX"
            + java.time.LocalDate.now().toString().replace("-", "")
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String id =
        db.insert(
            "INSERT INTO"
                + " repair_order(order_no,reporter_student_id,reporter_name,residence_id,room_id,room_no,building_id,building_name,title,description,category,priority,urgent_reason,contact_name,contact_phone,contact_time_slot,status,related_order_id,submitted_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            number,
            a.studentId(),
            h.get("studentName"),
            h.get("id"),
            h.get("roomId"),
            h.get("roomNo"),
            h.get("buildingId"),
            h.get("buildingName"),
            title,
            description,
            category,
            priority,
            urgent,
            name,
            phone,
            slot,
            "SUBMITTED",
            related,
            Timestamp.from(Instant.now()));
    String event = event(id, null, "SUBMITTED", "submit", "已提交报修");
    files.bind(Inputs.ids(b, "reportImageIds", 6), "REPAIR", id, "REPORT", event);
    var created = db.one("SELECT * FROM repair_order WHERE id=?", id);
    var window = visitValues(b, created, true);
    db.update(
        "UPDATE repair_order SET window_start=?,window_end=? WHERE id=?",
        window.start(),
        window.end(),
        id);
    dispatch.repairChanged(id, "submit", a.id());
    return detail(id);
  }

  String event(String id, String before, String after, String action, String note) {
    var a = auth.current();
    return db.insert(
        "INSERT INTO"
            + " repair_event(order_id,from_status,to_status,action,operator_id,operator_name,note,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        before,
        after,
        action,
        a.id(),
        a.name(),
        note,
        Timestamp.from(Instant.now()));
  }

  public Object action(String id, String action, Map<String, Object> b) {
    db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", id);
    var r = raw(id);
    ops.version(r, b);
    if (!allowed(r).contains(action))
      throw new ApiException(409, "ILLEGAL_REPAIR_ACTION", "当前身份或工单状态不能执行此操作");
    if (action.equals("accept")) {
      var settings =
          db.find(
              "SELECT accepting_new_tasks FROM dispatch_manager WHERE user_id=?",
              auth.current().id());
      if (settings != null && !Boolean.TRUE.equals(settings.get("acceptingNewTasks")))
        throw ApiException.conflict("你已暂停接受新任务，请先恢复接单");
    }
    String before = r.get("status").toString();
    String after =
        switch (action) {
          case "accept" -> "ACCEPTED";
          case "reject" -> "REJECTED";
          case "start", "rework" -> "PROCESSING";
          case "finish" -> "PENDING_CONFIRMATION";
          case "confirm" -> "COMPLETED";
          case "cancel" -> "CANCELLED";
          case "notes" -> before;
          default -> throw ApiException.bad("未知操作");
        };
    String note =
        switch (action) {
          case "finish" -> Inputs.text(b, "resultDescription", 1000, true);
          case "reject", "rework" -> Inputs.text(b, "reason", 1000, true);
          case "notes" -> Inputs.text(b, "note", 1000, true);
          default -> Inputs.text(b, "note", 1000, false);
        };
    if (action.equals("accept"))
      db.update(
          "UPDATE repair_order SET handler_user_id=?,handler_name=?,accepted_at=? WHERE id=?",
          auth.current().id(),
          auth.current().name(),
          Timestamp.from(Instant.now()),
          id);
    int changed =
        db.update(
            "UPDATE repair_order SET status=?,completed_at=?,version=version+1 WHERE id=? AND"
                + " version=?",
            after,
            after.equals("COMPLETED") ? Timestamp.from(Instant.now()) : null,
            id,
            Inputs.version(b));
    if (changed != 1) throw ApiException.conflict("工单已更新，请刷新");
    String event = event(id, before, after, action, note);
    if (action.equals("finish"))
      files.bind(Inputs.ids(b, "resultImageIds", 6), "REPAIR", id, "RESULT", event);
    ops.audit(
        "REPAIR_" + action.toUpperCase(), "REPAIR", id, note, Map.of("from", before, "to", after));
    dispatch.repairChanged(id, action, auth.current().id());
    return detail(id);
  }

  private record VisitValues(
      Timestamp start,
      Timestamp end,
      boolean confirmed,
      int minutes,
      boolean required,
      String waiting) {}

  private VisitValues visitValues(
      Map<String, Object> b, Map<String, Object> old, boolean creating) {
    Timestamp start = visitTime(b, old, "windowStart"), end = visitTime(b, old, "windowEnd");
    if ((start == null) != (end == null) || start != null && !end.after(start))
      throw ApiException.bad("上门时间需同时填写开始和结束，且结束晚于开始");
    boolean confirmed =
        creating
            ? false
            : Inputs.bool(b, "windowConfirmed", Boolean.TRUE.equals(old.get("windowConfirmed")));
    int minutes =
        creating
            ? (int) Db.number(old, "estimatedMinutes")
            : b.containsKey("estimatedMinutes")
                ? Inputs.integer(b, "estimatedMinutes", 1, 480)
                : (int) Db.number(old, "estimatedMinutes");
    boolean required =
        creating
            ? true
            : Inputs.bool(b, "visitRequired", Boolean.TRUE.equals(old.get("visitRequired")));
    String waiting =
        creating
            ? ""
            : b.containsKey("waitingReason")
                ? Inputs.text(b, "waitingReason", 500, false)
                : Objects.toString(old.get("waitingReason"), "");
    if (confirmed && start == null) throw ApiException.bad("确认预约需要明确的上门时间区间");
    if (!required && waiting.isBlank()) throw ApiException.bad("暂不安排上门需要填写原因");
    if (!creating && !auth.current().manager()) {
      boolean changedWindow =
          !Objects.equals(
                  start,
                  old.get("windowStart") == null
                      ? null
                      : Timestamp.from(Instant.parse(old.get("windowStart").toString())))
              || !Objects.equals(
                  end,
                  old.get("windowEnd") == null
                      ? null
                      : Timestamp.from(Instant.parse(old.get("windowEnd").toString())));
      if (minutes != Db.number(old, "estimatedMinutes")
          || required != Boolean.TRUE.equals(old.get("visitRequired"))
          || !waiting.equals(Objects.toString(old.get("waitingReason"), ""))
          || confirmed && (!Boolean.TRUE.equals(old.get("windowConfirmed")) || changedWindow))
        throw new ApiException(403, "VISIT_FIELD_FORBIDDEN", "学生只能修改上门偏好，预约确认与处理安排由办理宿管设置");
    }
    return new VisitValues(start, end, confirmed, minutes, required, waiting);
  }

  private Timestamp visitTime(Map<String, Object> b, Map<String, Object> old, String key) {
    if (!b.containsKey(key))
      return old.get(key) == null ? null : Timestamp.from(Instant.parse(old.get(key).toString()));
    return b.get(key) == null ? null : Inputs.time(b, key, Instant.now());
  }

  Object visit(String id, Map<String, Object> b) {
    db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", id);
    var order = raw(id);
    ops.version(order, b);
    if (!Set.of("SUBMITTED", "ACCEPTED", "PROCESSING").contains(order.get("status")))
      throw ApiException.conflict("当前工单状态不能修改上门安排");
    if (auth.current().manager()
        && order.get("handlerUserId") != null
        && !auth.current().id().equals(order.get("handlerUserId")))
      throw new ApiException(403, "NOT_TASK_OWNER", "仅办理宿管可修改已接单工单的上门安排");
    var values = visitValues(b, order, false);
    ops.changed(
        db.update(
            "UPDATE repair_order SET"
                + " window_start=?,window_end=?,window_confirmed=?,estimated_minutes=?,visit_required=?,waiting_reason=?,version=version+1"
                + " WHERE id=? AND version=?",
            values.start(),
            values.end(),
            values.confirmed(),
            values.minutes(),
            values.required(),
            values.waiting(),
            id,
            Inputs.version(b)));
    ops.audit(
        "REPAIR_VISIT_CHANGED",
        "REPAIR",
        id,
        "修改上门时间与安排",
        Map.of("windowConfirmed", values.confirmed(), "visitRequired", values.required()));
    dispatch.repairChanged(id, "visit", auth.current().id());
    return detail(id);
  }

  public Object handoff(String id, Map<String, Object> b) {
    db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", id);
    var order = raw(id);
    var actor = auth.current();
    actor.requireManager();
    if (!"ACCEPTED".equals(order.get("status")) || !actor.id().equals(order.get("handlerUserId")))
      throw ApiException.conflict("仅原办理宿管可以交接尚未开始的已接单任务");
    int orderVersion = Inputs.integer(b, "expectedOrderVersion", 0, Integer.MAX_VALUE);
    if (Db.number(order, "version") != orderVersion) throw ApiException.conflict("工单已更新，请刷新");
    var task = dispatch.activeClaim(id);
    if (task == null) throw ApiException.missing();
    task = db.one("SELECT * FROM dispatch_task WHERE id=? FOR UPDATE", task.get("id"));
    int taskVersion = Inputs.integer(b, "expectedTaskVersion", 0, Integer.MAX_VALUE);
    if (Db.number(task, "version") != taskVersion
        || Set.of("EN_ROUTE", "ON_SITE").contains(task.get("state")))
      throw ApiException.conflict("在途、已到达或已变化任务不能直接交接");
    String target = Long.toString(Inputs.id(b, "targetManagerId")),
        reason = Inputs.text(b, "reason", 500, true);
    if (target.equals(actor.id())) throw ApiException.bad("接收人不能是本人");
    var person = dispatch.targetManager(target);
    if (!dispatch.participating(person, Instant.now())
        || !DispatchRepository.buildingIds(person).contains(order.get("buildingId").toString()))
      throw ApiException.conflict("接收人需在线、接受新任务并具备该楼栋权限");
    ops.changed(
        db.update(
            "UPDATE repair_order SET handler_user_id=?,handler_name=?,version=version+1 WHERE id=?"
                + " AND version=? AND status='ACCEPTED'",
            target,
            person.get("displayName"),
            id,
            orderVersion));
    ops.changed(
        db.update(
            "UPDATE dispatch_task SET"
                + " manager_user_id=?,state='ASSIGNED',next_user_id=NULL,reason='',version=version+1,updated_at=?"
                + " WHERE id=? AND version=?",
            target,
            Timestamp.from(Instant.now()),
            task.get("id"),
            taskVersion));
    event(id, "ACCEPTED", "ACCEPTED", "handoff", reason + "；交接给" + person.get("displayName"));
    ops.audit(
        "REPAIR_HANDOFF",
        "REPAIR",
        id,
        reason,
        Map.of("fromManagerId", actor.id(), "toManagerId", target));
    dispatch.repairChanged(id, "handoff", actor.id());
    return detail(id);
  }
}
