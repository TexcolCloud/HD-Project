package cn.dormlink.followup;

import cn.dormlink.dispatch.DispatchTaskChanged;
import cn.dormlink.fieldservice.RemediationChanged;
import cn.dormlink.identity.AuthService;
import cn.dormlink.repairs.RepairChanged;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Transactional outbox and private inbox. Delivery here means available to authenticated polling.
 */
@Service
public class ServiceMessageService {
    private final Db db;
    private final AuthService auth;
    private final BusinessSupport ops;
    private final TransactionTemplate transactions;

    ServiceMessageService(
            Db db, AuthService auth, BusinessSupport ops, PlatformTransactionManager manager) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
        transactions = new TransactionTemplate(manager);
    }

    @EventListener
    void repairChanged(RepairChanged event) {
        var row =
                db.one(
                        "SELECT r.id,r.building_id,r.handler_user_id,s.user_id reporter_user_id"
                            + " FROM repair_order r JOIN student s ON s.id=r.reporter_student_id"
                            + " WHERE r.id=?",
                        event.order().id());
        String action = event.action().name();
        String title =
                switch (action) {
                    case "SUBMIT" -> "有新的宿舍报修";
                    case "VISIT" -> "报修上门安排已更新";
                    case "FINISH" -> "报修已处理，请确认结果";
                    case "CONFIRM" -> "报修已确认解决";
                    case "REWORK" -> "报修需要继续跟进";
                    case "HANDOFF", "TAKEOVER" -> "报修办理责任已交接";
                    default -> "报修进度已更新";
                };
        var recipients = new LinkedHashSet<String>();
        recipients.add(row.get("reporterUserId").toString());
        if (row.get("handlerUserId") != null) {
            recipients.add(row.get("handlerUserId").toString());
        }
        if ("SUBMIT".equals(action)) {
            recipients.addAll(managers(row.get("buildingId")));
        }
        for (String recipient : recipients) {
            enqueue(
                    "repair:" + event.order().id() + ":" + event.order().version() + ":" + action,
                    recipient,
                    row.get("buildingId"),
                    "REPAIR_" + action,
                    title,
                    "请打开应用查看工单详情；操作与责任以最新记录为准。",
                    "REPAIR",
                    event.order().id());
        }
    }

    @EventListener
    void dispatchChanged(DispatchTaskChanged event) {
        var row =
                db.one(
                        "SELECT s.user_id FROM repair_order r JOIN student s ON"
                                + " s.id=r.reporter_student_id WHERE r.id=?",
                        event.orderId());
        String title =
                switch (event.action()) {
                    case "DEPART" -> "宿管已出发";
                    case "ARRIVE" -> "宿管已登记到场";
                    case "BLOCK" -> "报修办理暂时受阻";
                    default -> "报修办理已恢复";
                };
        enqueue(
                "dispatch:" + event.orderId() + ":" + event.version() + ":" + event.action(),
                row.get("userId").toString(),
                event.buildingId(),
                "DISPATCH_" + event.action(),
                title,
                "请打开工单查看最新安排与说明。",
                "REPAIR",
                event.orderId());
    }

    @EventListener
    void remediationChanged(RemediationChanged event) {
        var recipients = new LinkedHashSet<String>();
        recipients.add(
                db.one("SELECT user_id FROM student WHERE id=?", event.studentId())
                        .get("userId")
                        .toString());
        if ("FEEDBACK".equals(event.action())) {
            recipients.addAll(managers(event.buildingId()));
        }
        for (String recipient : recipients) {
            enqueue(
                    "remediation:" + event.id() + ":" + event.version(),
                    recipient,
                    event.buildingId(),
                    "REMEDIATION_" + event.action(),
                    "宿舍卫生整改有新进展",
                    "请打开整改任务查看要求、反馈或复核结果。",
                    "REMEDIATION",
                    event.id());
        }
    }

    private List<String> managers(Object building) {
        return db
                .rows(
                        "SELECT u.id FROM sys_user u JOIN manager_building m ON"
                            + " m.manager_user_id=u.id WHERE m.building_id=? AND u.enabled=TRUE AND"
                            + " u.role='MANAGER'",
                        building)
                .stream()
                .map(row -> row.get("id").toString())
                .toList();
    }

    private void enqueue(
            String source,
            String recipient,
            Object building,
            String kind,
            String title,
            String body,
            String type,
            Object id) {
        // Producers hold the repair/task lock, or the sweep lock. Repeated facts in that
        // transaction
        // must observe an existing recipient row before another insert (also works on H2 fixtures).
        if (db.count(
                        "SELECT COUNT(*) FROM service_message WHERE source_key=? AND"
                                + " recipient_user_id=?",
                        source,
                        recipient)
                > 0) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        db.update(
                "INSERT INTO"
                    + " service_message(source_key,recipient_user_id,building_id,kind,title,body,business_type,business_id,created_at,next_attempt_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                source,
                recipient,
                building,
                kind,
                title,
                body,
                type,
                id,
                now,
                now);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 2000)
    public void deliver() {
        try {
            transactions.executeWithoutResult(
                    status -> {
                        var messages =
                                db.rows(
                                        "SELECT m.id,m.recipient_user_id,m.attempt_count FROM"
                                            + " service_message m WHERE (m.delivery_state='PENDING'"
                                            + " OR (m.delivery_state='FAILED' AND EXISTS(SELECT 1"
                                            + " FROM sys_user u WHERE u.id=m.recipient_user_id AND"
                                            + " u.enabled=TRUE))) AND m.next_attempt_at<=? ORDER BY"
                                            + " m.id LIMIT 100 FOR UPDATE",
                                        Timestamp.from(Instant.now()));
                        for (var message : messages) {
                            try {
                                boolean enabled =
                                        db.count(
                                                        "SELECT COUNT(*) FROM sys_user WHERE id=?"
                                                                + " AND enabled=TRUE",
                                                        message.get("recipientUserId"))
                                                > 0;
                                if (!enabled) {
                                    throw new IllegalStateException("RECIPIENT_DISABLED");
                                }
                                db.update(
                                        "UPDATE service_message SET"
                                            + " delivery_state='DELIVERED',delivered_at=?,last_error=NULL"
                                            + " WHERE id=?",
                                        Timestamp.from(Instant.now()),
                                        message.get("id"));
                            } catch (IllegalStateException unavailable) {
                                int attempts = (int) Db.number(message, "attemptCount") + 1;
                                db.update(
                                        "UPDATE service_message SET"
                                            + " attempt_count=?,delivery_state=?,last_error=?,next_attempt_at=?"
                                            + " WHERE id=?",
                                        attempts,
                                        attempts >= 5 ? "FAILED" : "PENDING",
                                        "RECIPIENT_UNAVAILABLE",
                                        Timestamp.from(
                                                Instant.now()
                                                        .plusSeconds(
                                                                Math.min(
                                                                        3600,
                                                                        30L
                                                                                << Math.min(
                                                                                        7,
                                                                                        attempts)))),
                                        message.get("id"));
                            }
                        }
                    });
        } catch (RuntimeException unavailable) {
            // The durable pending rows remain available on the next tick; no personal payload is
            // logged.
            LoggerFactory.getLogger(getClass())
                    .warn("Message delivery deferred: {}", unavailable.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void followUp() {
        try {
            transactions.executeWithoutResult(
                    status -> {
                        // Serialize only this bounded sweep across instances, never lock business
                        // rows.
                        var cursor =
                                db.one("SELECT * FROM service_followup_lock WHERE id=1 FOR UPDATE");
                        Instant now = Instant.now();
                        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
                        var pending =
                                db.rows(
                                        "SELECT r.id,r.building_id,r.handler_user_id,s.user_id"
                                            + " reporter_user_id FROM repair_order r JOIN student s"
                                            + " ON s.id=r.reporter_student_id WHERE"
                                            + " r.status='PENDING_CONFIRMATION' AND r.id>? AND"
                                            + " (SELECT MAX(e.created_at) FROM repair_event e WHERE"
                                            + " e.order_id=r.id AND"
                                            + " e.to_status='PENDING_CONFIRMATION')<? ORDER BY r.id"
                                            + " LIMIT 100",
                                        Db.number(cursor, "pendingCursor"),
                                        Timestamp.from(now.minusSeconds(86400)));
                        for (var row : pending) {
                            enqueue(
                                    "pending:" + row.get("id") + ":" + day,
                                    row.get("reporterUserId").toString(),
                                    row.get("buildingId"),
                                    "REPAIR_PENDING_REMINDER",
                                    "有报修等待您核对",
                                    "请查看处理结果并确认解决或反馈未解决。系统不会代您确认。",
                                    "REPAIR",
                                    row.get("id"));
                        }
                        advance("pending_cursor", pending);
                        var blocked =
                                db.rows(
                                        "SELECT r.id,r.building_id,r.handler_user_id FROM"
                                            + " repair_order r JOIN dispatch_task t ON"
                                            + " t.active_order_id=r.id WHERE t.state='BLOCKED' AND"
                                            + " t.updated_at<? AND r.handler_user_id IS NOT NULL"
                                            + " AND r.id>? ORDER BY r.id LIMIT 100",
                                        Timestamp.from(now.minusSeconds(86400)),
                                        Db.number(cursor, "blockedCursor"));
                        for (var row : blocked) {
                            enqueue(
                                    "blocked:" + row.get("id") + ":" + day,
                                    row.get("handlerUserId").toString(),
                                    row.get("buildingId"),
                                    "REPAIR_BLOCKED_REMINDER",
                                    "有受阻报修需要跟进",
                                    "请核对受阻原因并安排后续办理，原责任保持不变。",
                                    "REPAIR",
                                    row.get("id"));
                        }
                        advance("blocked_cursor", blocked);
                        var visitors =
                                db.rows(
                                        "SELECT id,building_id FROM visitor_registration WHERE"
                                            + " left_at IS NULL AND arrived_at<? AND id>? ORDER BY"
                                            + " id LIMIT 100",
                                        Timestamp.from(now.minusSeconds(12 * 3600L)),
                                        Db.number(cursor, "visitorCursor"));
                        for (var row : visitors) {
                            for (String manager : managers(row.get("buildingId"))) {
                                enqueue(
                                        "visitor:" + row.get("id") + ":" + day,
                                        manager,
                                        row.get("buildingId"),
                                        "VISITOR_CHECKOUT_REMINDER",
                                        "有较长时间未登记离访的记录",
                                        "请核对实际离访情况，系统不会自动补写离访。",
                                        "VISITOR",
                                        row.get("id"));
                            }
                        }
                        advance("visitor_cursor", visitors);
                        var remediation =
                                db.rows(
                                        "SELECT t.id,t.building_id,s.user_id FROM"
                                                + " hygiene_remediation t JOIN student s ON"
                                                + " s.id=t.responsible_student_id WHERE t.status IN"
                                                + " ('OPEN','SUBMITTED') AND t.due_at<? AND t.id>?"
                                                + " ORDER BY t.id LIMIT 100",
                                        Timestamp.from(now),
                                        Db.number(cursor, "remediationCursor"));
                        for (var row : remediation) {
                            var recipients = new LinkedHashSet<>(managers(row.get("buildingId")));
                            recipients.add(row.get("userId").toString());
                            for (String recipient : recipients) {
                                enqueue(
                                        "overdue:" + row.get("id") + ":" + day,
                                        recipient,
                                        row.get("buildingId"),
                                        "REMEDIATION_OVERDUE",
                                        "卫生整改已超过约定期限",
                                        "请查看任务并提交反馈或安排复核。",
                                        "REMEDIATION",
                                        row.get("id"));
                            }
                        }
                        advance("remediation_cursor", remediation);
                    });
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .warn("Service follow-up deferred: {}", unavailable.getClass().getSimpleName());
        }
    }

    private void advance(String column, List<Map<String, Object>> rows) {
        // Column names are private constants from the four sweeps above, never request input.
        db.update(
                "UPDATE service_followup_lock SET " + column + "=? WHERE id=1",
                rows.size() < 100 ? 0 : rows.getLast().get("id"));
    }

    private String scope() {
        var actor = auth.current();
        String sql = " m.recipient_user_id=" + Long.parseLong(actor.id());
        if (actor.manager()) {
            sql += " AND (m.building_id IS NULL OR " + ops.scope("m.building_id") + ")";
        }
        return sql;
    }

    Object list(Map<String, String> query) {
        String sql =
                "SELECT"
                    + " m.id,m.kind,m.title,m.body,m.business_type,m.business_id,m.created_at,m.read_at,m.version"
                    + " FROM service_message m WHERE m.delivery_state='DELIVERED' AND "
                        + scope();
        if ("true".equals(query.get("unreadOnly"))) {
            sql += " AND m.read_at IS NULL";
        }
        return ops.page(sql + " ORDER BY m.id DESC", List.of(), query);
    }

    Object summary() {
        String where = " FROM service_message m WHERE " + scope();
        return Map.of(
                "unreadCount",
                db.count(
                        "SELECT COUNT(*)"
                                + where
                                + " AND m.delivery_state='DELIVERED' AND m.read_at IS NULL"),
                "deliveryPending",
                db.count("SELECT COUNT(*)" + where + " AND m.delivery_state='PENDING'"),
                "deliveryFailed",
                db.count("SELECT COUNT(*)" + where + " AND m.delivery_state='FAILED'"));
    }

    Object read(String id, Map<String, Object> body) {
        var row =
                db.one(
                        "SELECT"
                            + " m.id,m.kind,m.title,m.body,m.business_type,m.business_id,m.created_at,m.read_at,m.version"
                            + " FROM service_message m WHERE m.id=? AND"
                            + " m.delivery_state='DELIVERED' AND "
                                + scope()
                                + " FOR UPDATE",
                        id);
        if (row.get("readAt") == null) {
            ops.version(row, body);
            db.update(
                    "UPDATE service_message SET read_at=?,version=version+1 WHERE id=?",
                    Timestamp.from(Instant.now()),
                    id);
        }
        return db.one(
                "SELECT id,kind,title,body,business_type,business_id,created_at,read_at,version"
                        + " FROM service_message WHERE id=?",
                id);
    }
}
