package cn.dormlink;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchPresence {
  final Db db;
  final AuthService auth;
  final BusinessSupport ops;
  final DispatchSignals signals;

  DispatchPresence(Db db, AuthService auth, BusinessSupport ops, DispatchSignals signals) {
    this.db = db;
    this.auth = auth;
    this.ops = ops;
    this.signals = signals;
  }

  boolean online(String userId, Instant now) {
    return db.count(
            "SELECT COUNT(*) FROM auth_session s JOIN sys_user u ON u.id=s.user_id WHERE"
                + " s.user_id=? AND u.role='MANAGER' AND u.enabled=TRUE AND s.revoked_at IS NULL"
                + " AND s.expires_at>? AND s.last_seen_at>=?",
            userId,
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(120)))
        > 0;
  }

  void ensureManager(String id) {
    if (db.count("SELECT COUNT(*) FROM dispatch_manager WHERE user_id=?", id) > 0) return;
    var first =
        db.find(
            "SELECT mb.building_id FROM manager_building mb JOIN building b ON b.id=mb.building_id"
                + " WHERE mb.manager_user_id=? AND b.enabled=TRUE ORDER BY mb.building_id LIMIT 1",
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
    var m =
        db.one(
            "SELECT s.*,u.display_name,b.name position_building_name FROM dispatch_manager s JOIN"
                + " sys_user u ON u.id=s.user_id LEFT JOIN building b ON"
                + " b.id=s.position_building_id WHERE s.user_id=?",
            id);
    m.put("online", online(id, Instant.now()));
    m.put(
        "buildingIds",
        db
            .rows(
                "SELECT building_id FROM manager_building WHERE manager_user_id=? ORDER BY"
                    + " building_id",
                id)
            .stream()
            .map(r -> r.get("buildingId"))
            .toList());
    return m;
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
            "UPDATE auth_session SET last_seen_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL"
                + " AND expires_at>?",
            Timestamp.from(Instant.now()),
            sessionId,
            a.id(),
            Timestamp.from(Instant.now()));
    if (affected != 1) throw new ApiException(401, "SESSION_EXPIRED", "会话已过期，请重新登录");
    if (!wasOnline) signals.changed(null, a.id(), "MANAGER_ONLINE");
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
      updated = null;
      if (target != null) {
        ops.building(target);
        var location =
            db.one("SELECT floor_count FROM building WHERE id=? AND enabled=TRUE", target);
        floor = Inputs.integer(b, "positionFloorNo", 1, (int) Db.number(location, "floorCount"));
        updated = Timestamp.from(Instant.now());
      }
    }
    Timestamp until =
        old.get("availableUntil") == null
            ? null
            : Timestamp.from(Instant.parse(old.get("availableUntil").toString()));
    if (b.containsKey("availableUntil")) {
      until =
          b.get("availableUntil") == null ? null : Inputs.time(b, "availableUntil", Instant.now());
      if (until != null
          && (until.toInstant().isBefore(Instant.now())
              || until.toInstant().isAfter(Instant.now().plusSeconds(86400 * 7))))
        throw ApiException.bad("可用时段结束需在未来7天内");
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
