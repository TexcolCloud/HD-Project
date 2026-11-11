package cn.dormlink.fieldservice;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.housing.HousingService;
import cn.dormlink.media.FileService;

import cn.dormlink.shared.api.ApiException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
  final Db db;
  final AuthService auth;
  final BusinessSupport ops;
  final FileService files;
  final HousingService housing;

  OperationsService(Db d, AuthService a, BusinessSupport o, FileService f, HousingService h) {
    db = d;
    auth = a;
    ops = o;
    files = f;
    housing = h;
  }

  Map<String, Object> hygieneRaw(String id) {
    var h = db.one("SELECT * FROM hygiene_inspection WHERE id=?", id);
    var a = auth.current();
    if (a.manager()) ops.building(h.get("buildingId"));
    else if (h.get("voidedAt") != null || !files.hygieneVisible(h, a.studentId()))
      throw ApiException.missing();
    return h;
  }

  Map<String, Object> hygiene(String id) {
    var h = hygieneRaw(id);
    h.put("images", files.assets("HYGIENE", id, "INSPECTION"));
    h.remove("activeGuard");
    return h;
  }

  Object hygieneList(Map<String, String> q, boolean mine) {
    var a = auth.current();
    if (mine) a.requireStudent();
    else a.requireManager();
    var args = new ArrayList<Object>();
    String sql = "SELECT h.* FROM hygiene_inspection h WHERE h.voided_at IS NULL AND ";
    if (a.manager()) sql += ops.scope("h.building_id");
    else {
      sql +=
          "EXISTS(SELECT 1 FROM residence x WHERE x.student_id=? AND x.room_id=h.room_id AND"
              + " x.checked_in_at<=h.inspected_at AND (x.checked_out_at IS NULL OR"
              + " x.checked_out_at>h.inspected_at))";
      args.add(a.studentId());
    }
    for (String key : List.of("buildingId", "roomId", "grade", "round", "inspectionDate"))
      sql =
          BusinessSupport.filter(
              sql,
              args,
              q,
              key,
              "h."
                  + switch (key) {
                    case "buildingId" -> "building_id";
                    case "roomId" -> "room_id";
                    case "inspectionDate" -> "inspection_date";
                    default -> key;
                  });
    sql = BusinessSupport.search(sql, args, q, "h.room_no", "h.building_name", "h.inspector_name");
    sql = BusinessSupport.dates(sql, args, q, "h.inspected_at");
    var page = ops.page(sql + " ORDER BY h.inspected_at DESC,h.id DESC", args, q);
    var images = files.assetsByBusiness("HYGIENE", BusinessSupport.items(page).stream()
        .map(row -> row.get("id").toString()).toList());
    for (var h : BusinessSupport.items(page)) {
      h.put("images", images.getOrDefault(h.get("id").toString(), List.of()));
      h.remove("activeGuard");
    }
    return page;
  }

  Object saveHygiene(String id, Map<String, Object> b) {
    var a = auth.current();
    a.requireManager();
    Map<String, Object> old = null;
    if (id != null) {
      db.one("SELECT id FROM hygiene_inspection WHERE id=? FOR UPDATE", id);
      old = hygieneRaw(id);
      ops.version(old, b);
      if (old.get("voidedAt") != null) throw ApiException.conflict("该检查已经作废");
      Inputs.text(b, "correctionReason", 1000, true);
    }
    String roomId = Long.toString(Inputs.id(b, "roomId"));
    var room = housing.room(roomId);
    if (old != null && !roomId.equals(old.get("roomId"))) throw ApiException.bad("修正评分不能改换宿舍");
    String date = Inputs.text(b, "inspectionDate", 10, true),
        round = Inputs.text(b, "round", 20, true);
    LocalDate day;
    try {
      day = LocalDate.parse(date);
    } catch (Exception e) {
      throw ApiException.bad("巡检日期格式无效");
    }
    if (day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw ApiException.bad("巡检日期不能是未来");
    Instant fallback =
        old == null ? Instant.now() : Instant.parse(old.get("inspectedAt").toString());
    Timestamp at = Inputs.time(b, "inspectedAt", fallback);
    Inputs.notFuture(at);
    if (!at.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(day))
      throw ApiException.bad("检查时刻与巡检日期不一致，补录请填写实际时刻");
    int floor = Inputs.integer(b, "floorScore", 0, 25),
        desk = Inputs.integer(b, "deskScore", 0, 25),
        bed = Inputs.integer(b, "bedScore", 0, 25),
        bath = Inputs.integer(b, "bathroomScore", 0, 25),
        total = floor + desk + bed + bath;
    String
        grade = total >= 90 ? "EXCELLENT" : total >= 80 ? "GOOD" : total >= 60 ? "PASS" : "RECTIFY",
        issue = Inputs.text(b, "issueNote", 500, total < 60),
        remediation = Inputs.text(b, "remediationNote", 500, false);
    Long related = Inputs.optionalId(b, "relatedInspectionId");
    if (related != null) {
      var previous = hygieneRaw(related.toString());
      if (!roomId.equals(previous.get("roomId"))) throw ApiException.bad("复查必须关联同一宿舍的原记录");
      if (related.toString().equals(id)) throw ApiException.bad("不能关联自身");
    }
    if (round.startsWith("RECHECK") && related == null) throw ApiException.bad("复查需要关联原巡检记录");
    if (id == null)
      id =
          db.insert(
              "INSERT INTO"
                  + " hygiene_inspection(room_id,room_no,building_id,building_name,inspection_date,round,inspected_at,floor_score,desk_score,bed_score,bathroom_score,total_score,grade,issue_note,remediation_note,inspector_user_id,inspector_name,related_inspection_id)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              roomId,
              room.get("roomNo"),
              room.get("buildingId"),
              room.get("buildingName"),
              day,
              round,
              at,
              floor,
              desk,
              bed,
              bath,
              total,
              grade,
              issue,
              remediation,
              a.id(),
              a.name(),
              related);
    else
      ops.changed(
          db.update(
              "UPDATE hygiene_inspection SET"
                  + " inspection_date=?,round=?,inspected_at=?,floor_score=?,desk_score=?,bed_score=?,bathroom_score=?,total_score=?,grade=?,issue_note=?,remediation_note=?,related_inspection_id=?,version=version+1"
                  + " WHERE id=? AND version=?",
              day,
              round,
              at,
              floor,
              desk,
              bed,
              bath,
              total,
              grade,
              issue,
              remediation,
              related,
              id,
              Inputs.version(b)));
    files.bind(Inputs.ids(b, "imageIds", 6), "HYGIENE", id, "INSPECTION", null);
    var updated = hygiene(id);
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("before", old);
    changes.put("after", updated);
    ops.audit(
        old == null ? "HYGIENE_CREATE" : "HYGIENE_CORRECT",
        "HYGIENE",
        id,
        Inputs.text(b, "correctionReason", 1000, false),
        changes);
    return updated;
  }

  Object voidHygiene(String id, Map<String, Object> b) {
    auth.current().requireManager();
    db.one("SELECT id FROM hygiene_inspection WHERE id=? FOR UPDATE", id);
    var old = hygieneRaw(id);
    ops.version(old, b);
    String reason = Inputs.text(b, "reason", 1000, true);
    if (old.get("voidedAt") != null) throw ApiException.conflict("记录已经作废");
    ops.changed(
        db.update(
            "UPDATE hygiene_inspection SET voided_at=?,active_guard=NULL,version=version+1 WHERE"
                + " id=? AND version=? AND voided_at IS NULL",
            Timestamp.from(Instant.now()),
            id,
            Inputs.version(b)));
    ops.audit("HYGIENE_VOID", "HYGIENE", id, reason, old);
    return hygiene(id);
  }

  Object hygieneHistory(String id) {
    auth.current().requireManager();
    hygieneRaw(id);
    return db.rows(
        "SELECT id,action,operator_name,note,changes,created_at FROM audit_event WHERE"
            + " business_type='HYGIENE' AND business_id=? ORDER BY id",
        id);
  }

  Map<String, Object> visitorRaw(String id) {
    auth.current().requireManager();
    var v = db.one("SELECT * FROM visitor_registration WHERE id=?", id);
    ops.building(v.get("buildingId"));
    return v;
  }

  Map<String, Object> visitor(String id) {
    return visitorView(visitorRaw(id));
  }

  Map<String, Object> visitorView(Map<String, Object> v) {
    v.put("phone", Inputs.mask(v.get("phone").toString()));
    v.put("status", v.get("leftAt") == null ? "VISITING" : "LEFT");
    return v;
  }

  Object visitors(Map<String, String> q) {
    auth.current().requireManager();
    var args = new ArrayList<Object>();
    String sql = "SELECT v.* FROM visitor_registration v WHERE " + ops.scope("v.building_id");
    sql = BusinessSupport.filter(sql, args, q, "buildingId", "v.building_id");
    sql = BusinessSupport.filter(sql, args, q, "roomId", "v.room_id");
    sql =
        BusinessSupport.search(
            sql, args, q, "v.visitor_name", "v.phone", "v.student_name", "v.room_no");
    sql = BusinessSupport.dates(sql, args, q, "v.arrived_at");
    if ("VISITING".equals(q.get("status"))) sql += " AND v.left_at IS NULL";
    if ("LEFT".equals(q.get("status"))) sql += " AND v.left_at IS NOT NULL";
    var page = ops.page(sql + " ORDER BY v.id DESC", args, q);
    for (var v : BusinessSupport.items(page)) visitorView(v);
    ops.audit("VISITOR_SEARCH", "VISITOR", null, "查询访客记录", Map.of("page", page.get("page")));
    return page;
  }

  public Object createVisitor(Map<String, Object> b) {
    var a = auth.current();
    a.requireManager();
    String studentId = Long.toString(Inputs.id(b, "visitedStudentId"));
    var h =
        db.find(
            "SELECT h.*,s.name student_name FROM residence h JOIN student s ON s.id=h.student_id"
                + " WHERE h.student_id=? AND h.checked_out_at IS NULL",
            studentId);
    if (h == null) throw ApiException.bad("被访学生尚未入住");
    ops.building(h.get("buildingId"));
    String name = Inputs.text(b, "visitorName", 50, true),
        phone = Inputs.phone(b, "phone"),
        purpose = Inputs.text(b, "purpose", 200, true),
        remark = Inputs.text(b, "remark", 500, false);
    if (!Inputs.bool(b, "confirmDuplicate", false)
        && db.count(
                "SELECT COUNT(*) FROM visitor_registration v WHERE v.phone=? AND v.left_at IS NULL"
                    + " AND "
                    + ops.scope("v.building_id"),
                phone)
            > 0)
      throw new ApiException(
          409, "VISITOR_DUPLICATE_CONFIRMATION_REQUIRED", "该联系方式已有在访记录，请核对是否确为另一次来访");
    Timestamp at = Inputs.time(b, "arrivedAt", Instant.now());
    Inputs.notFuture(at);
    String id =
        db.insert(
            "INSERT INTO"
                + " visitor_registration(visitor_name,phone,visited_student_id,student_name,room_id,room_no,building_id,building_name,purpose,remark,arrived_at,operator_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            name,
            phone,
            studentId,
            h.get("studentName"),
            h.get("roomId"),
            h.get("roomNo"),
            h.get("buildingId"),
            h.get("buildingName"),
            purpose,
            remark,
            at,
            a.id());
    ops.audit("VISITOR_ARRIVE", "VISITOR", id, "登记来访", Map.of("roomId", h.get("roomId")));
    return visitor(id);
  }

  public Object leave(String id, Map<String, Object> b) {
    db.one("SELECT id FROM visitor_registration WHERE id=? FOR UPDATE", id);
    var v = visitorRaw(id);
    ops.version(v, b);
    if (v.get("leftAt") != null) throw ApiException.conflict("该访客已登记离访");
    Timestamp at = Inputs.time(b, "leftAt", Instant.now());
    Inputs.notFuture(at);
    if (at.toInstant().isBefore(Instant.parse(v.get("arrivedAt").toString())))
      throw ApiException.bad("离访不能早于入访");
    ops.changed(
        db.update(
            "UPDATE visitor_registration SET left_at=?,left_operator_id=?,version=version+1 WHERE"
                + " id=? AND version=? AND left_at IS NULL",
            at,
            auth.current().id(),
            id,
            Inputs.version(b)));
    ops.audit("VISITOR_LEAVE", "VISITOR", id, "登记离访", Map.of("leftAt", at.toInstant().toString()));
    return visitor(id);
  }

  Object correctVisitor(String id, Map<String, Object> b) {
    db.one("SELECT id FROM visitor_registration WHERE id=? FOR UPDATE", id);
    var v = visitorRaw(id);
    ops.version(v, b);
    String reason = Inputs.text(b, "correctionReason", 1000, true);
    String
        name =
            b.containsKey("visitorName")
                ? Inputs.text(b, "visitorName", 50, true)
                : v.get("visitorName").toString(),
        phone = b.containsKey("phone") ? Inputs.phone(b, "phone") : v.get("phone").toString(),
        purpose =
            b.containsKey("purpose")
                ? Inputs.text(b, "purpose", 200, true)
                : v.get("purpose").toString(),
        remark =
            b.containsKey("remark")
                ? Inputs.text(b, "remark", 500, false)
                : v.get("remark").toString();
    Timestamp arrived = Inputs.time(b, "arrivedAt", Instant.parse(v.get("arrivedAt").toString()));
    Timestamp left =
        b.containsKey("leftAt")
            ? Inputs.time(b, "leftAt", Instant.now())
            : v.get("leftAt") == null
                ? null
                : Timestamp.from(Instant.parse(v.get("leftAt").toString()));
    Inputs.notFuture(arrived);
    if (left != null) {
      Inputs.notFuture(left);
      if (left.before(arrived)) throw ApiException.bad("离访不能早于入访");
    }
    ops.changed(
        db.update(
            "UPDATE visitor_registration SET"
                + " visitor_name=?,phone=?,purpose=?,remark=?,arrived_at=?,left_at=?,version=version+1"
                + " WHERE id=? AND version=?",
            name,
            phone,
            purpose,
            remark,
            arrived,
            left,
            id,
            Inputs.version(b)));
    ops.audit("VISITOR_CORRECT", "VISITOR", id, reason, Map.of("changedFields", b.keySet()));
    return visitor(id);
  }

  Object reveal(String id) {
    var v = visitorRaw(id);
    ops.audit("VISITOR_REVEAL_CONTACT", "VISITOR", id, "按需查看联系方式", Map.of());
    return Map.of("phone", v.get("phone"));
  }
}
