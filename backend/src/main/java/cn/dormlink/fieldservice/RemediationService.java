package cn.dormlink.fieldservice;

import cn.dormlink.identity.AuthService;
import cn.dormlink.media.FileService;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Keeps a scored inspection immutable while its separate corrective work is followed to review. */
@Service
class RemediationService {
    private final Db db;
    private final AuthService auth;
    private final BusinessSupport ops;
    private final FileService files;
    private final OperationsService inspections;
    private final ApplicationEventPublisher events;

    RemediationService(
            Db db,
            AuthService auth,
            BusinessSupport ops,
            FileService files,
            OperationsService inspections,
            ApplicationEventPublisher events) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
        this.files = files;
        this.inspections = inspections;
        this.events = events;
    }

    private Map<String, Object> raw(String id) {
        var row = db.one("SELECT * FROM hygiene_remediation WHERE id=?", id);
        var actor = auth.current();
        if (actor.manager()) {
            ops.building(row.get("buildingId"));
        } else if (!Objects.equals(actor.studentId(), row.get("responsibleStudentId"))) {
            throw ApiException.missing();
        }
        inspections.hygieneRaw(row.get("inspectionId").toString());
        return row;
    }

    Map<String, Object> detail(String id) {
        var row = raw(id);
        row.put(
                "overdue",
                List.of("OPEN", "SUBMITTED").contains(row.get("status"))
                        && Instant.parse(row.get("dueAt").toString()).isBefore(Instant.now()));
        row.put("evidenceImages", files.assets("REMEDIATION", id, "REMEDIATION"));
        return row;
    }

    void authorize(String id) {
        raw(id);
    }

    Object list(Map<String, String> query) {
        var actor = auth.current();
        var args = new ArrayList<Object>();
        String sql =
                "SELECT t.* FROM hygiene_remediation t JOIN hygiene_inspection h ON"
                        + " h.id=t.inspection_id WHERE ";
        if (actor.manager()) {
            sql += ops.scope("t.building_id");
        } else {
            sql +=
                    " h.voided_at IS NULL AND t.responsible_student_id=? AND EXISTS(SELECT 1 FROM"
                            + " residence x WHERE x.student_id=? AND x.room_id=h.room_id AND"
                            + " x.checked_in_at<=h.inspected_at AND (x.checked_out_at IS NULL OR"
                            + " x.checked_out_at>h.inspected_at))";
            args.add(actor.studentId());
            args.add(actor.studentId());
        }
        for (String key : List.of("status", "roomId", "buildingId", "inspectionId")) {
            sql =
                    BusinessSupport.filter(
                            sql,
                            args,
                            query,
                            key,
                            "t."
                                    + switch (key) {
                                        case "roomId" -> "room_id";
                                        case "buildingId" -> "building_id";
                                        case "inspectionId" -> "inspection_id";
                                        default -> key;
                                    });
        }
        if ("true".equals(query.get("overdueOnly"))) {
            sql += " AND t.status IN ('OPEN','SUBMITTED') AND t.due_at<?";
            args.add(Timestamp.from(Instant.now()));
        }
        var page = ops.page(sql + " ORDER BY t.due_at,t.id", args, query);
        var rows = BusinessSupport.items(page);
        var images =
                files.assetsByBusiness(
                        "REMEDIATION", rows.stream().map(r -> r.get("id").toString()).toList());
        for (var row : rows) {
            row.put(
                    "overdue",
                    List.of("OPEN", "SUBMITTED").contains(row.get("status"))
                            && Instant.parse(row.get("dueAt").toString()).isBefore(Instant.now()));
            row.put("evidenceImages", images.getOrDefault(row.get("id").toString(), List.of()));
        }
        return page;
    }

    Object create(Map<String, Object> body) {
        auth.current().requireManager();
        String inspectionId = Long.toString(Inputs.id(body, "inspectionId"));
        db.one("SELECT id FROM hygiene_inspection WHERE id=? FOR UPDATE", inspectionId);
        var inspection = inspections.hygieneRaw(inspectionId);
        if (inspection.get("voidedAt") != null) {
            throw ApiException.conflict("作废检查不能安排整改");
        }
        if (db.count("SELECT COUNT(*) FROM hygiene_remediation WHERE inspection_id=?", inspectionId)
                > 0) {
            throw ApiException.conflict("该巡检已有整改任务，请在原任务中跟进");
        }
        String student = Long.toString(Inputs.id(body, "responsibleStudentId"));
        var resident = responsible(student, inspection.get("roomId"), inspection);
        Inputs.text(body, "dueAt", 50, true);
        Timestamp due = Inputs.time(body, "dueAt", Instant.now());
        if (due == null
                || !due.toInstant().isAfter(Instant.now())
                || due.toInstant().isAfter(Instant.now().plusSeconds(90 * 86400L))) {
            throw ApiException.bad("整改期限须晚于当前时刻且在90天内");
        }
        String issue = Inputs.text(body, "issueNote", 500, true);
        String id =
                db.insert(
                        "INSERT INTO"
                            + " hygiene_remediation(inspection_id,room_id,room_no,building_id,building_name,responsible_student_id,responsible_name,issue_note,due_at,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                        inspectionId,
                        inspection.get("roomId"),
                        inspection.get("roomNo"),
                        inspection.get("buildingId"),
                        inspection.get("buildingName"),
                        student,
                        resident.get("name"),
                        issue,
                        due,
                        Timestamp.from(Instant.now()));
        changed(id, "CREATE", issue);
        return detail(id);
    }

    private Map<String, Object> responsible(
            String student, Object room, Map<String, Object> inspection) {
        var row =
                db.find(
                        "SELECT s.id,s.name FROM student s JOIN residence x ON x.student_id=s.id"
                                + " JOIN sys_user u ON u.id=s.user_id WHERE s.id=? AND"
                                + " s.status='ACTIVE' AND u.enabled=TRUE AND x.room_id=? AND"
                                + " x.checked_out_at IS NULL",
                        student,
                        room);
        if (row == null || !files.hygieneVisible(inspection, student)) {
            throw ApiException.bad("整改责任学生须为本宿舍当前在住且可查看原检查的学生");
        }
        return row;
    }

    Object feedback(String id, Map<String, Object> body) {
        auth.current().requireStudent();
        db.one("SELECT id FROM hygiene_remediation WHERE id=? FOR UPDATE", id);
        var row = raw(id);
        ops.version(row, body);
        if (!"OPEN".equals(row.get("status"))) {
            throw ApiException.conflict("当前任务不能提交整改反馈");
        }
        responsible(
                auth.current().studentId(),
                row.get("roomId"),
                inspections.hygieneRaw(row.get("inspectionId").toString()));
        String note = Inputs.text(body, "feedbackNote", 1000, true);
        archiveEvidence(row, "提交新的整改反馈，保留上一轮凭证");
        ops.changed(
                db.update(
                        "UPDATE hygiene_remediation SET"
                            + " status='SUBMITTED',feedback_note=?,submitted_at=?,version=version+1"
                            + " WHERE id=? AND version=? AND status='OPEN'",
                        note,
                        Timestamp.from(Instant.now()),
                        id,
                        Inputs.version(body)));
        files.bind(Inputs.ids(body, "imageIds", 6), "REMEDIATION", id, "REMEDIATION", null);
        changed(id, "FEEDBACK", note);
        return detail(id);
    }

    Object assign(String id, Map<String, Object> body) {
        auth.current().requireManager();
        db.one("SELECT id FROM hygiene_remediation WHERE id=? FOR UPDATE", id);
        var row = raw(id);
        ops.version(row, body);
        if (!List.of("OPEN", "SUBMITTED").contains(row.get("status"))) {
            throw ApiException.conflict("已结束的整改不能调整");
        }
        String student = Long.toString(Inputs.id(body, "responsibleStudentId"));
        var resident =
                responsible(
                        student,
                        row.get("roomId"),
                        inspections.hygieneRaw(row.get("inspectionId").toString()));
        String reason = Inputs.text(body, "reason", 1000, true);
        Inputs.text(body, "dueAt", 50, true);
        Timestamp due = Inputs.time(body, "dueAt", Instant.now());
        if (!due.toInstant().isAfter(Instant.now())
                || due.toInstant().isAfter(Instant.now().plusSeconds(90 * 86400L))) {
            throw ApiException.bad("整改期限须晚于当前时刻且在90天内");
        }
        archiveEvidence(row, "调整整改责任或期限，旧凭证不作为新责任人的提交");
        ops.changed(
                db.update(
                        "UPDATE hygiene_remediation SET"
                            + " responsible_student_id=?,responsible_name=?,due_at=?,"
                            + "status='OPEN',feedback_note='',review_note=?,submitted_at=NULL,related_recheck_id=NULL,version=version+1"
                            + " WHERE id=? AND version=?",
                        student,
                        resident.get("name"),
                        due,
                        reason,
                        id,
                        Inputs.version(body)));
        // Keep the former responsibility in the append-only audit and notify both accounts.
        ops.audit("REMEDIATION_REASSIGN_BEFORE", "REMEDIATION", id, reason, row);
        events.publishEvent(
                new RemediationChanged(
                        id,
                        row.get("buildingId").toString(),
                        row.get("responsibleStudentId").toString(),
                        (int) Db.number(row, "version") + 1,
                        "REASSIGN"));
        changed(id, "REASSIGN", reason);
        return detail(id);
    }

    Object cancel(String id, Map<String, Object> body) {
        auth.current().requireManager();
        db.one("SELECT id FROM hygiene_remediation WHERE id=? FOR UPDATE", id);
        var row = raw(id);
        ops.version(row, body);
        if (!List.of("OPEN", "SUBMITTED").contains(row.get("status"))) {
            throw ApiException.conflict("该整改已经结束");
        }
        String reason = Inputs.text(body, "reason", 1000, true);
        ops.changed(
                db.update(
                        "UPDATE hygiene_remediation SET"
                                + " status='CANCELLED',review_note=?,closed_at=?,version=version+1"
                                + " WHERE id=? AND version=?",
                        reason,
                        Timestamp.from(Instant.now()),
                        id,
                        Inputs.version(body)));
        changed(id, "CANCEL", reason);
        return detail(id);
    }

    Object review(String id, Map<String, Object> body) {
        auth.current().requireManager();
        var source = raw(id);
        Long recheck = Inputs.optionalId(body, "relatedRecheckId");
        var inspectionIds = new TreeSet<Long>();
        inspectionIds.add(Long.parseLong(source.get("inspectionId").toString()));
        if (recheck != null) {
            inspectionIds.add(recheck);
        }
        for (Long inspectionId : inspectionIds) {
            db.one("SELECT id FROM hygiene_inspection WHERE id=? FOR UPDATE", inspectionId);
        }
        db.one("SELECT id FROM hygiene_remediation WHERE id=? FOR UPDATE", id);
        var row = raw(id);
        ops.version(row, body);
        if (!"SUBMITTED".equals(row.get("status"))) {
            throw ApiException.conflict("请等待学生提交整改反馈后复核");
        }
        if (!(body.get("approved") instanceof Boolean)) {
            throw ApiException.bad("请明确选择验收通过或退回");
        }
        boolean approved = Inputs.bool(body, "approved", false);
        String note = Inputs.text(body, "reviewNote", 1000, true);
        if (approved) {
            if (recheck == null) {
                throw ApiException.bad("验收通过须关联合格的现场复查");
            }
            var check = inspections.hygieneRaw(recheck.toString());
            if (check.get("voidedAt") != null
                    || !row.get("roomId").equals(check.get("roomId"))
                    || !Objects.equals(row.get("inspectionId"), check.get("relatedInspectionId"))
                    || !check.get("round").toString().startsWith("RECHECK")
                    || Db.number(check, "totalScore") < 60) {
                throw ApiException.bad("须选择关联原检查、同宿舍且合格的有效复查");
            }
        }
        ops.changed(
                db.update(
                        "UPDATE hygiene_remediation SET"
                            + " status=?,review_note=?,related_recheck_id=?,closed_at=?,version=version+1"
                            + " WHERE id=? AND version=?",
                        approved ? "CLOSED" : "OPEN",
                        note,
                        approved ? recheck : null,
                        approved ? Timestamp.from(Instant.now()) : null,
                        id,
                        Inputs.version(body)));
        changed(id, approved ? "CLOSE" : "RETURN", note);
        return detail(id);
    }

    private void changed(String id, String action, String note) {
        var row = db.one("SELECT * FROM hygiene_remediation WHERE id=?", id);
        row.put("evidenceImages", files.assets("REMEDIATION", id, "REMEDIATION"));
        ops.audit("REMEDIATION_" + action, "REMEDIATION", id, note, row);
        events.publishEvent(
                new RemediationChanged(
                        id,
                        row.get("buildingId").toString(),
                        row.get("responsibleStudentId").toString(),
                        (int) Db.number(row, "version"),
                        action));
    }

    private void archiveEvidence(Map<String, Object> row, String note) {
        String id = row.get("id").toString();
        var images = files.assets("REMEDIATION", id, "REMEDIATION");
        if (images.isEmpty()) {
            return;
        }
        ops.audit(
                "REMEDIATION_ARCHIVE_EVIDENCE",
                "REMEDIATION",
                id,
                note,
                Map.of(
                        "version",
                        row.get("version"),
                        "responsibleStudentId",
                        row.get("responsibleStudentId"),
                        "images",
                        images));
        db.update(
                "UPDATE file_asset SET business_type='REMEDIATION_HISTORY' WHERE"
                        + " business_type='REMEDIATION' AND business_id=? AND state='BOUND'",
                id);
    }

    @EventListener
    void sourceVoided(HygieneVoided event) {
        var row =
                db.find(
                        "SELECT id FROM hygiene_remediation WHERE inspection_id=?"
                                + " AND status<>'CANCELLED' FOR UPDATE",
                        event.inspectionId());
        if (row != null) {
            archiveEvidence(
                    db.one("SELECT * FROM hygiene_remediation WHERE id=?", row.get("id")),
                    "原巡检作废，保留整改历史凭证");
            db.update(
                    "UPDATE hygiene_remediation SET"
                        + " status='CANCELLED',review_note=?,closed_at=?,version=version+1 WHERE"
                        + " id=?",
                    event.reason(),
                    Timestamp.from(Instant.now()),
                    row.get("id"));
            changed(row.get("id").toString(), "CANCEL", event.reason());
        }
        for (var task :
                db.rows(
                        "SELECT id FROM hygiene_remediation WHERE related_recheck_id=? AND"
                                + " status='CLOSED' FOR UPDATE",
                        event.inspectionId())) {
            String explanation = "验收依据已作废：" + event.reason();
            db.update(
                    "UPDATE hygiene_remediation SET"
                            + " status='OPEN',review_note=?,closed_at=NULL,version=version+1 WHERE"
                            + " id=?",
                    explanation.substring(0, Math.min(1000, explanation.length())),
                    task.get("id"));
            changed(task.get("id").toString(), "RETURN", "验收依据已作废，需重新反馈和复查");
        }
    }
}
