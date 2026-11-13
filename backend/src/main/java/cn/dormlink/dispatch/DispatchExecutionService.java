package cn.dormlink.dispatch;

import cn.dormlink.identity.AuthService;
import cn.dormlink.mobility.MobilityService;
import cn.dormlink.repairs.RepairService;
import cn.dormlink.repairs.RepairSnapshot;
import cn.dormlink.repairs.RepairTakeover;
import cn.dormlink.repairs.RepairTransfer;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Explicit task execution and transfer; all called inside the command transaction. */
@Service
class DispatchExecutionService {
    private final Db db;
    private final AuthService auth;
    private final BusinessSupport ops;
    private final DispatchRepository repository;
    private final DispatchPresence presence;
    private final RepairService repairs;
    private final DispatchSignals signals;
    private final MobilityService mobility;
    private final DispatchService views;

    DispatchExecutionService(
            Db db,
            AuthService auth,
            BusinessSupport ops,
            DispatchRepository repository,
            DispatchPresence presence,
            RepairService repairs,
            DispatchSignals signals,
            MobilityService mobility,
            DispatchService views) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
        this.repository = repository;
        this.presence = presence;
        this.repairs = repairs;
        this.signals = signals;
        this.mobility = mobility;
        this.views = views;
    }

    Object claim(String orderId, Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        var manager = presence.manager(a.id());
        if (!repository.participating(manager, Instant.now())) {
            throw ApiException.conflict("请先保持在线并开启接受新任务");
        }
        if (b.get("expectedRevision") != null
                && Db.number(repository.revision(), "revision")
                        != Inputs.integer(b, "expectedRevision", 0, Integer.MAX_VALUE)) {
            throw ApiException.conflict("推荐计划已更新，请刷新");
        }
        return repairs.accept(
                orderId, Inputs.integer(b, "expectedOrderVersion", 0, Integer.MAX_VALUE));
    }

    Object handoff(String orderId, Map<String, Object> b) {
        var actor = auth.current();
        actor.requireManager();
        db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", orderId);
        var order = repairs.snapshot(orderId);
        if (order.status() != RepairSnapshot.Status.ACCEPTED
                || !actor.id().equals(order.handlerUserId())) {
            throw ApiException.conflict("仅原办理宿管可以交接尚未开始的已接单任务");
        }
        int orderVersion = Inputs.integer(b, "expectedOrderVersion", 0, Integer.MAX_VALUE);
        if (order.version() != orderVersion) {
            throw ApiException.conflict("工单已更新，请刷新");
        }
        var task = lockedClaim(orderId);
        int taskVersion = Inputs.integer(b, "expectedTaskVersion", 0, Integer.MAX_VALUE);
        if (Db.number(task, "version") != taskVersion
                || Set.of("EN_ROUTE", "ON_SITE").contains(task.get("state"))) {
            throw ApiException.conflict("在途、已到达或已变化任务不能直接交接");
        }
        String target = Long.toString(Inputs.id(b, "targetManagerId"));
        String reason = Inputs.text(b, "reason", 500, true);
        if (target.equals(actor.id())) {
            throw ApiException.bad("接收人不能是本人");
        }
        var person = presence.manager(target);
        if (!repository.participating(person, Instant.now())
                || !DispatchRepository.buildingIds(person).contains(order.buildingId())) {
            throw ApiException.conflict("接收人需在线、接受新任务并具备该楼栋权限");
        }
        ops.changed(
                db.update(
                        "UPDATE dispatch_task SET"
                            + " manager_user_id=?,state='ASSIGNED',next_user_id=NULL,reason='',version=version+1,updated_at=?"
                            + " WHERE id=? AND version=?",
                        target,
                        Timestamp.from(Instant.now()),
                        task.get("id"),
                        taskVersion));
        return repairs.transfer(
                new RepairTransfer(
                        orderId,
                        orderVersion,
                        target,
                        person.get("displayName").toString(),
                        reason));
    }

    private Map<String, Object> lockedClaim(String orderId) {
        var claim = repository.activeClaim(orderId);
        if (claim == null) {
            throw ApiException.missing();
        }
        return db.one("SELECT * FROM dispatch_task WHERE id=? FOR UPDATE", claim.get("id"));
    }

    Object takeover(String orderId, Map<String, Object> body) {
        var actor = auth.current();
        actor.requireManager();
        db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", orderId);
        var order = repairs.snapshot(orderId);
        String reason = Inputs.text(body, "reason", 500, true);
        int orderVersion = Inputs.integer(body, "expectedOrderVersion", 0, Integer.MAX_VALUE);
        int taskVersion = Inputs.integer(body, "expectedTaskVersion", 0, Integer.MAX_VALUE);
        if (!Set.of(RepairSnapshot.Status.ACCEPTED, RepairSnapshot.Status.PROCESSING)
                        .contains(order.status())
                || order.handlerUserId() == null
                || actor.id().equals(order.handlerUserId())
                || order.version() != orderVersion) {
            throw ApiException.conflict("仅可接续其他宿管负责且尚未完工的任务，请刷新后核对");
        }
        var task = lockedClaim(orderId);
        if (Db.number(task, "version") != taskVersion) {
            throw ApiException.conflict("任务已更新，请刷新");
        }
        var successor = presence.manager(actor.id());
        if (!repository.participating(successor, Instant.now())
                || !DispatchRepository.buildingIds(successor).contains(order.buildingId())) {
            throw ApiException.conflict("接续人须在线、接受任务且具有该楼栋权限");
        }
        // Repair ownership is locked above. Do not invert command's actor -> repair lock order by
        // taking a second user's lock here; a concurrent original-owner command rechecks ownership.
        var owner =
                db.one("SELECT id,role,enabled FROM sys_user WHERE id=?", order.handlerUserId());
        boolean lostEligibility =
                !Boolean.TRUE.equals(owner.get("enabled"))
                        || !"MANAGER".equals(owner.get("role"))
                        || db.count(
                                        "SELECT COUNT(*) FROM manager_building WHERE"
                                                + " manager_user_id=? AND building_id=?",
                                        order.handlerUserId(),
                                        order.buildingId())
                                == 0;
        if (!lostEligibility) {
            Instant grace = Instant.now().minusSeconds(600);
            boolean recentLogin =
                    db.count(
                                    "SELECT COUNT(*) FROM auth_session WHERE user_id=? AND"
                                            + " last_seen_at>?",
                                    order.handlerUserId(),
                                    Timestamp.from(grace))
                            > 0;
            boolean recentWork =
                    db.count(
                                    "SELECT COUNT(*) FROM field_work_session WHERE user_id=?"
                                            + " AND (lease_expires_at>? OR started_at>?)",
                                    order.handlerUserId(),
                                    Timestamp.from(grace),
                                    Timestamp.from(grace))
                            > 0;
            var accepted =
                    db.one("SELECT accepted_at FROM repair_order WHERE id=?", orderId)
                            .get("acceptedAt");
            boolean justAccepted =
                    accepted != null && Instant.parse(accepted.toString()).isAfter(grace);
            if (recentLogin || recentWork || justAccepted) {
                throw ApiException.conflict("原办理人仍可接续或尚在10分钟失联宽限内，请联系原办理人正常交接");
            }
        }
        String previousState = task.get("state").toString();
        ops.changed(
                db.update(
                        "UPDATE dispatch_task SET"
                            + " manager_user_id=?,state='BLOCKED',next_user_id=NULL,reason=?,version=version+1,updated_at=?"
                            + " WHERE id=? AND version=?",
                        actor.id(),
                        reason,
                        Timestamp.from(Instant.now()),
                        task.get("id"),
                        taskVersion));
        mobility.abandonLeg(order.handlerUserId(), orderId);
        ops.audit(
                "DISPATCH_TAKEOVER",
                "REPAIR",
                orderId,
                reason,
                Map.of(
                        "previousState",
                        previousState,
                        "fromManagerId",
                        order.handlerUserId(),
                        "toManagerId",
                        actor.id()));
        return repairs.takeover(
                new RepairTakeover(orderId, orderVersion, order.handlerUserId(), reason));
    }

    Object action(String orderId, String action, Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", orderId);
        var order = repairs.snapshot(orderId);
        if (!a.id().equals(order.handlerUserId())) {
            throw new ApiException(403, "NOT_TASK_OWNER", "仅办理宿管可以操作任务");
        }
        var claim = lockedClaim(orderId);
        int version = Inputs.integer(b, "expectedTaskVersion", 0, Integer.MAX_VALUE);
        if (Db.number(claim, "version") != version) {
            throw ApiException.conflict("任务已更新，请刷新");
        }
        String old = claim.get("state").toString(), next;
        switch (action) {
            case "depart" -> {
                if (!old.equals("ASSIGNED")) {
                    throw ApiException.conflict("只有未出发任务可以出发");
                }
                next = "EN_ROUTE";
            }
            case "arrive" -> {
                if (!old.equals("EN_ROUTE")) {
                    throw ApiException.conflict("只有在途任务可以登记到达");
                }
                next = "ON_SITE";
            }
            case "block" -> {
                Inputs.text(b, "reason", 500, true);
                next = "BLOCKED";
            }
            case "resume" -> {
                if (!old.equals("BLOCKED")) {
                    throw ApiException.conflict("只有受阻任务可以恢复");
                }
                next = "ASSIGNED";
            }
            default -> throw ApiException.bad("未知任务操作");
        }
        if ((action.equals("depart") || action.equals("arrive"))
                && db.count(
                                "SELECT COUNT(*) FROM dispatch_task WHERE manager_user_id=? AND"
                                    + " active_order_id IS NOT NULL AND order_id<>? AND state IN"
                                    + " ('ON_SITE','EN_ROUTE')",
                                a.id(),
                                orderId)
                        > 0) {
            throw ApiException.conflict("请先完成或标记受阻的当前任务，不能同时执行另一站");
        }
        String reason = Inputs.text(b, "reason", 500, action.equals("block"));
        ops.changed(
                db.update(
                        "UPDATE dispatch_task SET"
                                + " state=?,next_user_id=?,reason=?,version=version+1,updated_at=?"
                                + " WHERE id=? AND version=?",
                        next,
                        next.equals("EN_ROUTE") ? a.id() : null,
                        reason,
                        Timestamp.from(Instant.now()),
                        claim.get("id"),
                        version));
        if (action.equals("arrive")) {
            mobility.arrival(a, orderId);
            var room = db.one("SELECT floor_no FROM dorm_room WHERE id=?", order.roomId());
            db.update(
                    "UPDATE dispatch_manager SET"
                        + " position_building_id=?,position_floor_no=?,position_updated_at=?,version=version+1"
                        + " WHERE user_id=?",
                    order.buildingId(),
                    room.get("floorNo"),
                    Timestamp.from(Instant.now()),
                    a.id());
        }
        if (action.equals("depart")) {
            mobility.departure(a, orderId);
        }
        if (action.equals("block")) {
            mobility.abandonLeg(a.id(), orderId);
        }
        ops.audit(
                "DISPATCH_" + action.toUpperCase(Locale.ROOT),
                "REPAIR",
                orderId,
                reason,
                Map.of("from", old, "to", next));
        signals.taskChanged(
                orderId, order.buildingId(), action.toUpperCase(Locale.ROOT), a.id(), version + 1);
        return views.task(repository.activeOrder(orderId), a.id(), null, 0);
    }
}
