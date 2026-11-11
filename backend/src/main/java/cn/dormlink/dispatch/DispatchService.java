package cn.dormlink.dispatch;

import cn.dormlink.identity.AuthService;
import cn.dormlink.mobility.MobilityService;
import cn.dormlink.repairs.RepairService;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
class DispatchService {
    final Db db;
    final AuthService auth;
    final BusinessSupport ops;
    final DispatchRepository repository;
    final DispatchPresence presence;
    final DispatchCoordinator coordinator;
    final RepairService repairs;
    final DispatchSignals signals;
    final MobilityService mobility;

    DispatchService(
            Db db,
            AuthService auth,
            BusinessSupport ops,
            DispatchRepository repository,
            DispatchPresence presence,
            DispatchCoordinator coordinator,
            RepairService repairs,
            DispatchSignals signals,
            MobilityService mobility) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
        this.repository = repository;
        this.presence = presence;
        this.coordinator = coordinator;
        this.repairs = repairs;
        this.signals = signals;
        this.mobility = mobility;
    }

    Set<String> scope() {
        auth.current().requireManager();
        return new HashSet<>(
                db
                        .rows(
                                "SELECT building_id FROM manager_building WHERE manager_user_id=?",
                                auth.current().id())
                        .stream()
                        .map(r -> r.get("buildingId").toString())
                        .toList());
    }

    Map<String, Object> visibleManager(Map<String, Object> manager, Set<String> scope) {
        var out = new LinkedHashMap<>(manager);
        out.put(
                "buildingIds",
                DispatchRepository.buildingIds(manager).stream().filter(scope::contains).toList());
        if (manager.get("positionBuildingId") != null
                && !scope.contains(manager.get("positionBuildingId").toString())) {
            out.put("positionBuildingId", null);
            out.put("positionBuildingName", null);
            out.put("positionFloorNo", null);
            out.put("positionUpdatedAt", null);
        }
        return out;
    }

    Map<String, Object> task(
            Map<String, Object> row, String managerId, RoutePlanner.Stop stop, int sequence) {
        var out = new LinkedHashMap<String, Object>();
        for (String k :
                List.of(
                        "orderNo",
                        "title",
                        "description",
                        "buildingId",
                        "buildingName",
                        "roomId",
                        "roomNo",
                        "floorNo",
                        "priority",
                        "handlerUserId",
                        "windowStart",
                        "windowEnd",
                        "windowConfirmed")) {
            out.put(k, row.get(k));
        }
        out.put("orderId", row.get("id"));
        out.put("repairStatus", row.get("status"));
        out.put("managerUserId", managerId);
        out.put("state", row.get("taskState") == null ? "RECOMMENDED" : row.get("taskState"));
        out.put("sequence", sequence);
        out.put(
                "locked",
                Set.of("EN_ROUTE", "ON_SITE").contains(Objects.toString(row.get("taskState"), "")));
        out.put("estimatedArrival", stop == null ? null : stop.etaStart().toString());
        out.put("estimatedFinish", stop == null ? null : stop.etaEnd().toString());
        out.put("travelMinutes", stop == null ? 0 : stop.travelMinutes());
        out.put("serviceMinutes", Db.number(row, "estimatedMinutes"));
        out.put("version", row.get("taskVersion") == null ? 0 : row.get("taskVersion"));
        out.put("orderVersion", row.get("version"));
        out.put(
                "reason",
                Objects.toString(row.get("taskReason"), "").isBlank()
                        ? "行程按楼栋/楼层估算；未人工定位时使用默认出发点"
                        : row.get("taskReason"));
        return out;
    }

    Object board() {
        return repository.readPlan(this::boardLocked);
    }

    private Object boardLocked() {
        var scope = scope();
        var snapshot = repository.snapshot(false);
        var current = repository.resultAt(snapshot.revision());
        var revision = repository.revision();
        var people =
                snapshot.managers().stream()
                        .filter(
                                m ->
                                        DispatchRepository.buildingIds(m).stream()
                                                .anyMatch(scope::contains))
                        .toList();
        Map<String, Map<String, Object>> visible = new LinkedHashMap<>();
        for (var row : snapshot.orders()) {
            if (scope.contains(row.get("buildingId").toString())) {
                visible.put(row.get("id").toString(), row);
            }
        }
        Map<String, RoutePlanner.Route> routes = new HashMap<>();
        if (current != null) {
            for (var route : current.routes()) {
                routes.put(route.managerId(), route);
            }
        }
        Set<String> placed = new HashSet<>();
        var outputRoutes = new ArrayList<Map<String, Object>>();
        int travel = 0, maxLoad = 0;
        for (var manager : people) {
            String id = manager.get("userId").toString();
            var route = routes.get(id);
            var stops = new ArrayList<Map<String, Object>>();
            int minutes = 0, load = 0, waiting = 0;
            RoutePlanner.Stop previous = null;
            boolean previousVisible = true;
            if (route != null) {
                for (var stop : route.stops()) {
                    var row = visible.get(stop.orderId());
                    boolean allowed =
                            row != null
                                    && (row.get("handlerUserId") == null
                                            || id.equals(row.get("handlerUserId")));
                    if (!allowed) {
                        previous = stop;
                        previousVisible = false;
                        continue;
                    }
                    if (previousVisible) {
                        Instant earliest =
                                previous == null
                                        ? DispatchRepository.instant(revision.get("generatedAt"))
                                        : previous.etaEnd();
                        if (earliest != null) {
                            waiting +=
                                    Math.max(
                                            0,
                                            (int)
                                                    Duration.between(
                                                                    earliest.plusSeconds(
                                                                            stop.travelMinutes()
                                                                                    * 60L),
                                                                    stop.etaStart())
                                                            .toMinutes());
                        }
                    }
                    stops.add(task(row, id, stop, stops.size() + 1));
                    placed.add(stop.orderId());
                    minutes += stop.travelMinutes();
                    load += stop.travelMinutes() + stop.serviceMinutes();
                    previous = stop;
                    previousVisible = true;
                }
            }
            load += waiting;
            outputRoutes.add(
                    Map.of(
                            "manager",
                            visibleManager(manager, scope),
                            "tasks",
                            stops,
                            "travelMinutes",
                            minutes,
                            "waitingMinutes",
                            waiting,
                            "workloadMinutes",
                            load));
            travel += minutes;
            maxLoad = Math.max(maxLoad, load);
        }
        Map<String, RoutePlanner.Unassigned> reasons = new HashMap<>();
        if (current != null) {
            for (var item : current.unassigned()) {
                reasons.put(item.orderId(), item);
            }
        }
        Map<String, Map<String, Object>> excluded = new HashMap<>();
        for (var item : snapshot.excluded()) {
            excluded.put(item.get("id").toString(), item);
        }
        var unassigned = new ArrayList<Map<String, Object>>();
        for (var entry : visible.entrySet()) {
            if (placed.contains(entry.getKey())) {
                continue;
            }
            var row = entry.getValue();
            var explanation = reasons.get(entry.getKey());
            var skip = excluded.get(entry.getKey());
            String code =
                    skip != null
                            ? skip.get("reasonCode").toString()
                            : explanation == null ? "REPLAN_PENDING" : explanation.code();
            String reason =
                    skip != null
                            ? skip.get("reason").toString()
                            : explanation == null ? "正在重新规划，请刷新查看最新版本" : explanation.reason();
            long candidates =
                    people.stream()
                            .filter(
                                    m ->
                                            repository.participating(m, Instant.now())
                                                    && DispatchRepository.buildingIds(m)
                                                            .contains(
                                                                    row.get("buildingId")
                                                                            .toString())
                                                    && (row.get("handlerUserId") == null
                                                            || row.get("handlerUserId")
                                                                    .equals(m.get("userId"))))
                            .count();
            unassigned.add(
                    Map.of(
                            "task",
                            task(row, Objects.toString(row.get("handlerUserId"), null), null, 0),
                            "reasonCode",
                            code,
                            "reason",
                            reason,
                            "candidateCount",
                            candidates));
        }
        var me =
                people.stream()
                        .filter(m -> m.get("userId").equals(auth.current().id()))
                        .findFirst()
                        .orElseGet(() -> presence.manager(auth.current().id()));
        var metrics =
                Map.of(
                        "totalTasks",
                        visible.size(),
                        "assignedCount",
                        placed.size(),
                        "unassignedCount",
                        unassigned.size(),
                        "travelMinutes",
                        travel,
                        "baselineTravelMinutes",
                        current != null && visible.size() == snapshot.orders().size()
                                ? current.metrics().baselineTravelMinutes()
                                : -1,
                        "maxWorkloadMinutes",
                        maxLoad);
        var out = new LinkedHashMap<String, Object>();
        out.put("revision", revision.get("revision"));
        out.put(
                "generatedAt",
                revision.get("generatedAt") == null
                        ? Instant.now().toString()
                        : revision.get("generatedAt"));
        out.put("dirty", Boolean.TRUE.equals(revision.get("dirty")) || signals.pending());
        out.put(
                "onlineCount",
                people.stream().filter(m -> Boolean.TRUE.equals(m.get("online"))).count());
        out.put(
                "participatingCount",
                people.stream().filter(m -> repository.participating(m, Instant.now())).count());
        out.put("me", visibleManager(me, scope));
        out.put("routes", outputRoutes);
        out.put("unassigned", unassigned);
        out.put("metrics", metrics);
        out.put("changes", changes(snapshot.revision(), current, visible));
        return out;
    }

    private List<String> changes(
            long revision, RoutePlanner.Result current, Map<String, Map<String, Object>> visible) {
        if (current == null) {
            return List.of("等待首次计划");
        }
        var before = repository.resultAt(revision - 1);
        Map<String, String> old = new HashMap<>(), now = new LinkedHashMap<>();
        if (before != null) {
            for (var route : before.routes()) {
                for (int i = 0; i < route.stops().size(); i++) {
                    old.put(route.stops().get(i).orderId(), route.managerId() + ":" + (i + 1));
                }
            }
        }
        for (var route : current.routes()) {
            for (int i = 0; i < route.stops().size(); i++) {
                now.put(route.stops().get(i).orderId(), route.managerId() + ":" + (i + 1));
            }
        }
        var changes = new ArrayList<String>();
        for (var entry : visible.entrySet()) {
            String id = entry.getKey(), number = entry.getValue().get("orderNo").toString();
            if (now.containsKey(id) && !old.containsKey(id)) {
                changes.add("新增路线站点：" + number);
            } else if (now.containsKey(id) && !Objects.equals(now.get(id), old.get(id))) {
                changes.add("调整访问顺序或推荐人员：" + number);
            } else if (!now.containsKey(id) && old.containsKey(id)) {
                changes.add("移回待安排：" + number);
            }
            if (changes.size() >= 20) {
                break;
            }
        }
        return changes.isEmpty() ? List.of("剩余访问顺序保持稳定；版本 " + revision) : changes;
    }

    Object replan(Map<String, Object> b) {
        auth.current().requireManager();
        if (b.get("expectedRevision") != null
                && Db.number(repository.revision(), "revision")
                        != Inputs.integer(b, "expectedRevision", 0, Integer.MAX_VALUE)) {
            throw ApiException.conflict("计划已更新，请刷新后重新规划");
        }
        signals.changed(null, auth.current().id(), "MANUAL_REPLAN");
        coordinator.replan();
        return board();
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
        return repairs.action(
                orderId,
                "accept",
                Map.of(
                        "expectedVersion",
                        Inputs.integer(b, "expectedOrderVersion", 0, Integer.MAX_VALUE)));
    }

    Object handoff(String orderId, Map<String, Object> b) {
        return repairs.handoff(orderId, b);
    }

    Object action(String orderId, String action, Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        db.one("SELECT id FROM repair_order WHERE id=? FOR UPDATE", orderId);
        var order = repairs.raw(orderId);
        if (!a.id().equals(order.get("handlerUserId"))) {
            throw new ApiException(403, "NOT_TASK_OWNER", "仅办理宿管可以操作任务");
        }
        var claim = repository.activeClaim(orderId);
        if (claim == null) {
            throw ApiException.missing();
        }
        claim = db.one("SELECT * FROM dispatch_task WHERE id=? FOR UPDATE", claim.get("id"));
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
            var room = db.one("SELECT floor_no FROM dorm_room WHERE id=?", order.get("roomId"));
            db.update(
                    "UPDATE dispatch_manager SET"
                        + " position_building_id=?,position_floor_no=?,position_updated_at=?,version=version+1"
                        + " WHERE user_id=?",
                    order.get("buildingId"),
                    room.get("floorNo"),
                    Timestamp.from(Instant.now()),
                    a.id());
        }
        if (action.equals("depart")) {
            mobility.departure(a, orderId);
        }
        ops.audit(
                "DISPATCH_" + action.toUpperCase(Locale.ROOT),
                "REPAIR",
                orderId,
                reason,
                Map.of("from", old, "to", next));
        signals.changed(orderId, a.id(), "TASK_" + action.toUpperCase(Locale.ROOT));
        var row =
                repository.orders(false).stream()
                        .filter(r -> orderId.equals(r.get("id")))
                        .findFirst()
                        .orElseThrow(ApiException::missing);
        return task(row, a.id(), null, 0);
    }

    Object travel() {
        var scope = scope();
        var links =
                db
                        .rows(
                                "SELECT from_building_id,to_building_id,minutes,estimated,version"
                                        + " FROM dispatch_travel ORDER BY"
                                        + " from_building_id,to_building_id")
                        .stream()
                        .filter(
                                r ->
                                        scope.contains(r.get("fromBuildingId").toString())
                                                && scope.contains(r.get("toBuildingId").toString()))
                        .toList();
        return Map.of("floorMinutes", 2, "sameFloorMinutes", 1, "links", links);
    }

    Object saveTravel(String from, String to, Map<String, Object> b) {
        ops.building(from);
        ops.building(to);
        if (from.equals(to)) {
            throw ApiException.bad("跨楼通行配置需要不同楼栋");
        }
        int minutes = Inputs.integer(b, "minutes", 0, 240), version = Inputs.version(b);
        var old =
                db.find(
                        "SELECT * FROM dispatch_travel WHERE from_building_id=? AND"
                                + " to_building_id=? FOR UPDATE",
                        from,
                        to);
        if (old == null) {
            if (version != 0) {
                throw ApiException.conflict("通行配置已变化");
            }
            db.update(
                    "INSERT INTO dispatch_travel(from_building_id,to_building_id,minutes)"
                            + " VALUES(?,?,?)",
                    from,
                    to,
                    minutes);
        } else {
            ops.version(old, b);
            ops.changed(
                    db.update(
                            "UPDATE dispatch_travel SET minutes=?,version=version+1 WHERE"
                                    + " from_building_id=? AND to_building_id=? AND version=?",
                            minutes,
                            from,
                            to,
                            version));
        }
        signals.changed(null, auth.current().id(), "TRAVEL_CHANGED");
        return db.one(
                "SELECT from_building_id,to_building_id,minutes,estimated,version FROM"
                        + " dispatch_travel WHERE from_building_id=? AND to_building_id=?",
                from,
                to);
    }
}
