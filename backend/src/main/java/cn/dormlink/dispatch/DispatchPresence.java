package cn.dormlink.dispatch;

import cn.dormlink.identity.AuthService;
import cn.dormlink.mobility.MobilityService;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
class DispatchPresence {
    final Db db;
    final AuthService auth;
    final BusinessSupport ops;
    final DispatchSignals signals;
    final MobilityService mobility;

    DispatchPresence(
            Db db,
            AuthService auth,
            BusinessSupport ops,
            DispatchSignals signals,
            MobilityService mobility) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
        this.signals = signals;
        this.mobility = mobility;
    }

    boolean online(String userId, Instant now) {
        return db.count(
                        "SELECT COUNT(*) FROM auth_session s JOIN sys_user u ON u.id=s.user_id"
                                + " WHERE s.user_id=? AND u.role='MANAGER' AND u.enabled=TRUE AND"
                                + " s.revoked_at IS NULL AND s.expires_at>? AND s.last_seen_at>=?",
                        userId,
                        Timestamp.from(now),
                        Timestamp.from(now.minusSeconds(120)))
                > 0;
    }

    void ensureManager(String id) {
        if (db.count("SELECT COUNT(*) FROM dispatch_manager WHERE user_id=?", id) > 0) {
            return;
        }
        var first =
                db.find(
                        "SELECT mb.building_id FROM manager_building mb JOIN building b ON"
                            + " b.id=mb.building_id WHERE mb.manager_user_id=? AND b.enabled=TRUE"
                            + " ORDER BY mb.building_id LIMIT 1",
                        id);
        db.update(
                "INSERT INTO"
                    + " dispatch_manager(user_id,position_building_id,position_floor_no,available_until)"
                    + " VALUES(?,?,?,?)",
                id,
                first == null ? null : first.get("buildingId"),
                first == null ? null : 1,
                Timestamp.from(Instant.now().plusSeconds(28800)));
    }

    Map<String, Object> manager(String id) {
        return managers().stream()
                .filter(manager -> id.equals(manager.get("userId")))
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    List<Map<String, Object>> managers() {
        Instant now = Instant.now();
        var managers =
                db.rows(
                        "SELECT s.*,u.display_name,b.name position_building_name,EXISTS(SELECT 1"
                            + " FROM auth_session a WHERE a.user_id=u.id AND a.revoked_at IS NULL"
                            + " AND a.expires_at>? AND a.last_seen_at>=?) online FROM"
                            + " dispatch_manager s JOIN sys_user u ON u.id=s.user_id LEFT JOIN"
                            + " building b ON b.id=s.position_building_id WHERE u.role='MANAGER'"
                            + " AND u.enabled=TRUE ORDER BY u.id",
                        Timestamp.from(now),
                        Timestamp.from(now.minusSeconds(120)));
        var scopes = new HashMap<String, List<Object>>();
        for (var scope :
                db.rows(
                        "SELECT manager_user_id,building_id FROM manager_building ORDER BY"
                                + " manager_user_id,building_id")) {
            scopes.computeIfAbsent(
                            scope.get("managerUserId").toString(), ignored -> new ArrayList<>())
                    .add(scope.get("buildingId"));
        }
        var active = mobility.activeUserIds(now);
        var contexts = mobility.plannerContexts(now);
        var names = new HashMap<String, String>();
        if (!contexts.isEmpty()) {
            for (var building : db.rows("SELECT id,name FROM building WHERE enabled=TRUE")) {
                names.put(building.get("id").toString(), building.get("name").toString());
            }
        }
        for (var manager : managers) {
            String id = manager.get("userId").toString();
            manager.put("buildingIds", scopes.getOrDefault(id, List.of()));
            manager.put(
                    "online", Boolean.TRUE.equals(manager.get("online")) || active.contains(id));
            var context = contexts.get(id);
            Instant manualUpdated =
                    manager.get("positionUpdatedAt") == null
                            ? null
                            : Instant.parse(manager.get("positionUpdatedAt").toString());
            // A newer device observation also fences old manual positions after it becomes
            // unusable.
            // Only a subsequent explicit operator correction may replace that observation barrier.
            if (context != null
                    && (manualUpdated == null || !manualUpdated.isAfter(context.observedAt()))) {
                manager.put("positionBuildingId", context.buildingId());
                manager.put("positionFloorNo", context.floorNo());
                manager.put("positionUpdatedAt", context.observedAt().toString());
                manager.put("positionBuildingName", names.get(context.buildingId()));
            }
        }
        return managers;
    }

    @Transactional
    public Map<String, Object> heartbeat(String sessionId) {
        var a = auth.current();
        a.requireManager();
        db.one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", a.id());
        boolean wasOnline = online(a.id(), Instant.now());
        ensureManager(a.id());
        int affected =
                db.update(
                        "UPDATE auth_session SET last_seen_at=? WHERE id=? AND user_id=? AND"
                                + " revoked_at IS NULL AND expires_at>?",
                        Timestamp.from(Instant.now()),
                        sessionId,
                        a.id(),
                        Timestamp.from(Instant.now()));
        if (affected != 1) {
            throw new ApiException(401, "SESSION_EXPIRED", "会话已过期，请重新登录");
        }
        if (!wasOnline) {
            signals.changed(null, a.id(), "MANAGER_ONLINE");
        }
        return manager(a.id());
    }

    Map<String, Object> settings(Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        ensureManager(a.id());
        var old = db.one("SELECT * FROM dispatch_manager WHERE user_id=? FOR UPDATE", a.id());
        ops.version(old, b);
        boolean accepting = Inputs.bool(b, "acceptingNewTasks", true);
        Object building = old.get("positionBuildingId"),
                floor = old.get("positionFloorNo"),
                updated = old.get("positionUpdatedAt");
        if (b.containsKey("positionBuildingId")) {
            Long target = Inputs.optionalId(b, "positionBuildingId");
            building = target;
            floor = null;
            updated = Timestamp.from(Instant.now());
            if (target != null) {
                ops.building(target);
                var location =
                        db.one(
                                "SELECT floor_count FROM building WHERE id=? AND enabled=TRUE",
                                target);
                floor =
                        Inputs.integer(
                                b, "positionFloorNo", 1, (int) Db.number(location, "floorCount"));
                updated = Timestamp.from(Instant.now());
            }
        }
        Timestamp until =
                old.get("availableUntil") == null
                        ? null
                        : Timestamp.from(Instant.parse(old.get("availableUntil").toString()));
        if (b.containsKey("availableUntil")) {
            until =
                    b.get("availableUntil") == null
                            ? null
                            : Inputs.time(b, "availableUntil", Instant.now());
            if (until != null
                    && (until.toInstant().isBefore(Instant.now())
                            || until.toInstant().isAfter(Instant.now().plusSeconds(86400 * 7)))) {
                throw ApiException.bad("可用时段结束需在未来7天内");
            }
        }
        ops.changed(
                db.update(
                        "UPDATE dispatch_manager SET"
                            + " accepting_new_tasks=?,position_building_id=?,position_floor_no=?,position_updated_at=?,available_until=?,version=version+1"
                            + " WHERE user_id=? AND version=?",
                        accepting,
                        building,
                        floor,
                        updated instanceof String s ? Timestamp.from(Instant.parse(s)) : updated,
                        until,
                        a.id(),
                        Inputs.version(b)));
        signals.changed(null, a.id(), "MANAGER_SETTINGS_CHANGED");
        return manager(a.id());
    }
}
