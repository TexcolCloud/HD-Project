package cn.dormlink.assistance;

import cn.dormlink.identity.AuthService;
import cn.dormlink.repairs.RepairService;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Durable provenance and append-only decisions; an AI suggestion never executes a repair command.
 */
@Service
class AssistFeedbackService {
    private final Db db;
    private final AuthService auth;
    private final RepairService repairs;
    private final BusinessSupport ops;

    AssistFeedbackService(Db db, AuthService auth, RepairService repairs, BusinessSupport ops) {
        this.db = db;
        this.auth = auth;
        this.repairs = repairs;
        this.ops = ops;
    }

    @Transactional
    public void register(Map<String, Object> advice) {
        String id = advice.get("suggestionId").toString();
        var invocation =
                db.one("SELECT id,user_id,status FROM ai_invocation WHERE id=? FOR UPDATE", id);
        if (!auth.current().id().equals(invocation.get("userId"))
                || !"SUCCESS".equals(invocation.get("status"))) {
            throw ApiException.missing();
        }
        if (!(advice.get("evidence") instanceof List<?> evidence) || evidence.size() > 20) {
            throw ApiException.bad("AI事实关联无效");
        }
        for (var entry : evidence) {
            if (!(entry instanceof Map<?, ?> fact) || fact.get("orderId") == null) {
                throw ApiException.bad("AI事实关联无效");
            }
            var order = repairs.snapshot(fact.get("orderId").toString());
            db.update(
                    "INSERT INTO ai_advice_order(suggestion_id,order_id,user_id,created_at) SELECT"
                            + " ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM ai_advice_order WHERE"
                            + " suggestion_id=? AND order_id=?)",
                    id,
                    order.id(),
                    auth.current().id(),
                    Timestamp.from(Instant.now()),
                    id,
                    order.id());
        }
    }

    Object list(String order) {
        auth.current().requireManager();
        repairs.snapshot(order);
        var rows =
                db.rows(
                        "SELECT"
                            + " id,order_id,suggestion_id,decision,reason,outcome,operator_name,user_id,created_at"
                            + " FROM ai_advice_feedback WHERE order_id=? ORDER BY id DESC LIMIT 50",
                        order);
        for (var row : rows) {
            row.put("canFollowUp", auth.current().id().equals(row.remove("userId")));
        }
        return Map.of("items", rows);
    }

    Object record(String order, Map<String, Object> body) {
        auth.current().requireManager();
        repairs.snapshot(order);
        String suggestion = Long.toString(Inputs.id(body, "suggestionId"));
        if (db.count(
                        "SELECT COUNT(*) FROM ai_advice_order WHERE suggestion_id=? AND order_id=?"
                                + " AND user_id=?",
                        suggestion,
                        order,
                        auth.current().id())
                == 0) {
            throw ApiException.missing();
        }
        String decision = Inputs.choice(body, "decision", "ADOPTED", "MODIFIED", "IGNORED");
        String reason = Inputs.text(body, "reason", 1000, true);
        String outcome = Inputs.text(body, "outcome", 1000, false);
        String id =
                db.insert(
                        "INSERT INTO"
                            + " ai_advice_feedback(order_id,suggestion_id,user_id,operator_name,decision,reason,outcome,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?)",
                        order,
                        suggestion,
                        auth.current().id(),
                        auth.current().name(),
                        decision,
                        reason,
                        outcome,
                        Timestamp.from(Instant.now()));
        ops.audit(
                "AI_ADVICE_FEEDBACK",
                "REPAIR",
                order,
                reason,
                Map.of("feedbackId", id, "decision", decision));
        var row =
                db.one(
                        "SELECT"
                            + " id,order_id,suggestion_id,decision,reason,outcome,operator_name,created_at"
                            + " FROM ai_advice_feedback WHERE id=?",
                        id);
        row.put("canFollowUp", true);
        return row;
    }
}
