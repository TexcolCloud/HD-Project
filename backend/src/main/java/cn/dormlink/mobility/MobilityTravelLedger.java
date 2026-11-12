package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityRepository.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Learning accepts only explicit visit actions bounded by fresh entrance confirmations. */
final class MobilityTravelLedger {
    private final MobilityRepository repository;
    private final Clock clock;

    MobilityTravelLedger(MobilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    void departure(Actor actor, String orderId) {
        Work work = eligible(actor);
        Position position = confirmed(actor);
        if (work == null || position == null) {
            return;
        }
        var from = repository.anchor(position.anchorId(), actor.id());
        var target =
                first(
                        repository.query(
                                "SELECT building_id FROM repair_order WHERE id=?",
                                (row, index) -> row.getString(1),
                                id(orderId)));
        Integer predicted = null;
        if (from != null && target != null) {
            String edge = from.buildingId() + ":" + target;
            var baseline =
                    first(
                            repository.query(
                                    "SELECT minutes FROM dispatch_travel WHERE from_building_id=?"
                                            + " AND to_building_id=?",
                                    (row, index) -> row.getInt(1),
                                    id(from.buildingId()),
                                    id(target)));
            if (baseline != null) {
                var shared = minutes(Map.of(edge, baseline));
                predicted =
                        personalMinutes(shared, actor.id())
                                        .getOrDefault(actor.id(), Map.of())
                                        .getOrDefault(edge, shared.get(edge))
                                * 60;
            }
        }
        repository.update("DELETE FROM mobility_pending_leg WHERE user_id=?", id(actor.id()));
        repository.update(
                "INSERT INTO"
                    + " mobility_pending_leg(user_id,session_id,source_epoch,order_id,from_anchor_id,departed_at,predicted_seconds)"
                    + " VALUES(?,?,?,?,?,?,?)",
                id(actor.id()),
                work.view().id(),
                work.view().sourceEpoch(),
                id(orderId),
                position.anchorId(),
                ts(clock.instant()),
                predicted);
    }

    private record Pending(
            String sessionId,
            int epoch,
            String anchorId,
            Instant departedAt,
            Integer predictedSeconds) {}

    void arrival(Actor actor, String orderId) {
        var pending =
                repository.query(
                        "SELECT"
                            + " session_id,source_epoch,from_anchor_id,departed_at,predicted_seconds"
                            + " FROM mobility_pending_leg WHERE user_id=? AND order_id=? FOR"
                            + " UPDATE",
                        (row, index) ->
                                new Pending(
                                        row.getString(1),
                                        row.getInt(2),
                                        row.getString(3),
                                        time(row, "departed_at"),
                                        integer(row, "predicted_seconds")),
                        id(actor.id()),
                        id(orderId));
        if (pending.isEmpty()) {
            return;
        }
        repository.update("DELETE FROM mobility_pending_leg WHERE user_id=?", id(actor.id()));
        Work work = eligible(actor);
        Position position = confirmed(actor);
        Pending leg = pending.getFirst();
        if (work == null
                || position == null
                || !work.view().id().equals(leg.sessionId())
                || work.view().sourceEpoch() != leg.epoch()
                || !position.anchorConfirmedAt().isAfter(leg.departedAt())) {
            return;
        }
        var from = repository.anchor(leg.anchorId(), actor.id());
        var to = repository.anchor(position.anchorId(), actor.id());
        int seconds = (int) Duration.between(leg.departedAt(), clock.instant()).getSeconds();
        if (from == null
                || to == null
                || !from.enabled()
                || !to.enabled()
                || from.buildingId().equals(to.buildingId())
                || seconds < 10
                || seconds > 1800) {
            return;
        }
        var target =
                repository.query(
                        "SELECT building_id FROM repair_order WHERE id=?",
                        (row, index) -> row.getString(1),
                        id(orderId));
        if (target.isEmpty() || !to.buildingId().equals(target.getFirst())) {
            return;
        }
        // The configured edge is an existing, stable pair lock even before a learned model exists.
        var baseline =
                repository.query(
                        "SELECT minutes FROM dispatch_travel WHERE from_building_id=? AND"
                                + " to_building_id=? FOR UPDATE",
                        (row, index) -> row.getInt(1),
                        id(from.buildingId()),
                        id(to.buildingId()));
        if (baseline.isEmpty()) {
            return;
        }
        repository.update(
                "INSERT INTO"
                    + " travel_leg_summary(id,session_id,from_anchor_id,to_anchor_id,from_building_id,to_building_id,duration_seconds,quality,created_at,predicted_seconds)"
                    + " VALUES(?,?,?,?,?,?,?,'CONFIRMED',?,?)",
                UUID.randomUUID().toString(),
                work.view().id(),
                from.id(),
                to.id(),
                id(from.buildingId()),
                id(to.buildingId()),
                seconds,
                ts(clock.instant()),
                leg.predictedSeconds());
        refresh(from.buildingId(), to.buildingId());
        refreshPersonal(actor.id(), from.buildingId(), to.buildingId());
    }

    private Work eligible(Actor actor) {
        if (!actor.manager() || !"MOBILE".equals(actor.clientType())) {
            return null;
        }
        Work work = repository.current(actor.id(), false);
        if (work == null
                || !"ACTIVE".equals(work.view().state())
                || !Objects.equals(actor.sessionId(), work.authSessionId())
                || !work.view().leaseExpiresAt().isAfter(clock.instant())) {
            return null;
        }
        return repository
                        .query(
                                "SELECT a.id FROM auth_session a JOIN sys_user u ON u.id=a.user_id"
                                    + " WHERE a.id=? AND a.revoked_at IS NULL AND a.expires_at>?"
                                    + " AND u.enabled=TRUE AND u.role='MANAGER'",
                                (row, index) -> row.getLong(1),
                                id(actor.sessionId()),
                                ts(clock.instant()))
                        .isEmpty()
                ? null
                : work;
    }

    private Position confirmed(Actor actor) {
        Position position = repository.position(actor.id());
        if (position == null
                || position.anchorConfirmedAt() == null
                || position.anchorConfirmedAt().isBefore(clock.instant().minusSeconds(30))) {
            return null;
        }
        var anchor = repository.anchor(position.anchorId(), actor.id());
        if (anchor == null
                || !anchor.enabled()
                || anchor.floorNo() != null && anchor.floorNo() != 1
                || position.view().buildingId() == null) {
            return null;
        }
        return position;
    }

    private record ModelRow(int minutes, int samples, int sessions, boolean enabled, int version) {}

    private ModelRow model(String from, String to) {
        return first(
                repository.query(
                        "SELECT minutes,sample_count,session_count,enabled,version FROM"
                            + " mobility_travel_model WHERE from_building_id=? AND to_building_id=?"
                            + " FOR UPDATE",
                        (row, index) ->
                                new ModelRow(
                                        row.getInt(1),
                                        row.getInt(2),
                                        row.getInt(3),
                                        row.getBoolean(4),
                                        row.getInt(5)),
                        id(from),
                        id(to)));
    }

    private void refresh(String from, String to) {
        var legs =
                repository.query(
                        "SELECT session_id,duration_seconds FROM travel_leg_summary WHERE"
                            + " from_building_id=? AND to_building_id=? AND quality='CONFIRMED' AND"
                            + " created_at>? ORDER BY created_at DESC LIMIT 200",
                        (row, index) -> new TravelLearning.Leg(row.getString(1), row.getInt(2)),
                        id(from),
                        id(to),
                        ts(clock.instant().minusSeconds(30L * 86400)));
        var learned = TravelLearning.learn(legs);
        if (learned.isEmpty()) {
            return;
        }
        ModelRow previous = model(from, to);
        if (previous != null && !previous.enabled()) {
            return;
        }
        var next = learned.orElseThrow();
        int version = previous == null ? 0 : previous.version() + 1;
        if (previous == null) {
            repository.update(
                    "INSERT INTO"
                        + " mobility_travel_model(from_building_id,to_building_id,minutes,sample_count,session_count,enabled,version,updated_at)"
                        + " VALUES(?,?,?,?,?,TRUE,?,?)",
                    id(from),
                    id(to),
                    next.minutes(),
                    next.sampleCount(),
                    next.sessionCount(),
                    version,
                    ts(clock.instant()));
        } else {
            repository.update(
                    "UPDATE mobility_travel_model SET"
                        + " minutes=?,sample_count=?,session_count=?,version=?,updated_at=? WHERE"
                        + " from_building_id=? AND to_building_id=?",
                    next.minutes(),
                    next.sampleCount(),
                    next.sessionCount(),
                    version,
                    ts(clock.instant()),
                    id(from),
                    id(to));
        }
        revision(
                from,
                to,
                version,
                next.minutes(),
                true,
                next.sampleCount(),
                next.sessionCount(),
                "CONFIRMED_ENTRANCE_SEGMENTS");
    }

    Map<String, Integer> minutes(Map<String, Integer> baseline) {
        Map<String, Integer> result = new HashMap<>(baseline);
        repository.query(
                "SELECT from_building_id,to_building_id,minutes FROM mobility_travel_model WHERE"
                        + " enabled=TRUE AND sample_count>=5 AND session_count>=3 AND updated_at>?",
                (row, index) -> {
                    String key = row.getString(1) + ":" + row.getString(2);
                    if (result.containsKey(key)) {
                        result.put(key, row.getInt(3));
                    }
                    return key;
                },
                ts(clock.instant().minusSeconds(30L * 86400)));
        return Map.copyOf(result);
    }

    /** Per-person entrance durations use the same evidence threshold as shared models. */
    private void refreshPersonal(String userId, String from, String to) {
        var legs =
                repository.query(
                        "SELECT l.session_id,l.duration_seconds FROM travel_leg_summary l JOIN"
                            + " field_work_session f ON f.id=l.session_id WHERE f.user_id=? AND"
                            + " l.from_building_id=? AND l.to_building_id=? AND"
                            + " l.quality='CONFIRMED' AND l.created_at>? ORDER BY l.created_at DESC"
                            + " LIMIT 200",
                        (row, index) -> new TravelLearning.Leg(row.getString(1), row.getInt(2)),
                        id(userId),
                        id(from),
                        id(to),
                        ts(clock.instant().minusSeconds(30L * 86400)));
        var learned = TravelLearning.learn(legs);
        if (learned.isEmpty()) {
            return;
        }
        var next = learned.orElseThrow();
        int changed =
                repository.update(
                        "UPDATE mobility_personal_travel_model SET"
                                + " minutes=?,sample_count=?,session_count=?,updated_at=? WHERE"
                                + " user_id=? AND from_building_id=? AND to_building_id=?",
                        next.minutes(),
                        next.sampleCount(),
                        next.sessionCount(),
                        ts(clock.instant()),
                        id(userId),
                        id(from),
                        id(to));
        if (changed == 0) {
            repository.update(
                    "INSERT INTO"
                        + " mobility_personal_travel_model(user_id,from_building_id,to_building_id,minutes,sample_count,session_count,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    id(userId),
                    id(from),
                    id(to),
                    next.minutes(),
                    next.sampleCount(),
                    next.sessionCount(),
                    ts(clock.instant()));
        }
    }

    Map<String, Map<String, Integer>> personalMinutes(Map<String, Integer> shared) {
        return personalMinutes(shared, null);
    }

    private Map<String, Map<String, Integer>> personalMinutes(
            Map<String, Integer> shared, String userId) {
        Map<String, Map<String, Integer>> result = new TreeMap<>();
        var parameters = new ArrayList<Object>();
        parameters.add(ts(clock.instant().minusSeconds(30L * 86400)));
        if (userId != null) {
            parameters.add(id(userId));
        }
        repository.query(
                "SELECT p.user_id,p.from_building_id,p.to_building_id,p.minutes FROM"
                    + " mobility_personal_travel_model p JOIN sys_user u ON u.id=p.user_id JOIN"
                    + " manager_building mf ON mf.manager_user_id=p.user_id AND"
                    + " mf.building_id=p.from_building_id JOIN manager_building mt ON"
                    + " mt.manager_user_id=p.user_id AND mt.building_id=p.to_building_id LEFT JOIN"
                    + " mobility_travel_model m ON m.from_building_id=p.from_building_id AND"
                    + " m.to_building_id=p.to_building_id WHERE u.enabled=TRUE AND u.role='MANAGER'"
                    + " AND p.sample_count>=5 AND p.session_count>=3 AND p.updated_at>? AND"
                    + " (m.enabled IS NULL OR m.enabled=TRUE)"
                        + (userId == null ? "" : " AND p.user_id=?")
                        + " ORDER BY p.user_id,p.from_building_id,p.to_building_id LIMIT 5000",
                (row, index) -> {
                    String key = row.getString(2) + ":" + row.getString(3);
                    Integer baseline = shared.get(key);
                    if (baseline != null && baseline > 0) {
                        int bounded =
                                Math.max(
                                        Math.max(1, (int) Math.ceil(baseline * .75)),
                                        Math.min((int) Math.ceil(baseline * 1.5), row.getInt(4)));
                        result.computeIfAbsent(row.getString(1), ignored -> new TreeMap<>())
                                .put(key, bounded);
                    }
                    return key;
                },
                parameters.toArray());
        Map<String, Map<String, Integer>> frozen = new TreeMap<>();
        result.forEach((user, values) -> frozen.put(user, Map.copyOf(values)));
        return Map.copyOf(frozen);
    }

    MobilityModels.Efficiency efficiency(Actor actor) {
        var rows =
                repository.query(
                        "SELECT COUNT(*) sample_count,COUNT(l.predicted_seconds)"
                            + " measured_count,AVG(CASE WHEN l.predicted_seconds IS NOT NULL THEN"
                            + " ABS(l.duration_seconds-l.predicted_seconds)/60.0 ELSE NULL END)"
                            + " mean_error,SUM(l.predicted_seconds)/60.0 predicted_minutes,SUM(CASE"
                            + " WHEN l.predicted_seconds IS NOT NULL THEN l.duration_seconds ELSE"
                            + " NULL END)/60.0 actual_minutes FROM travel_leg_summary l JOIN"
                            + " field_work_session f ON f.id=l.session_id JOIN manager_building mf"
                            + " ON mf.manager_user_id=f.user_id AND"
                            + " mf.building_id=l.from_building_id JOIN manager_building mt ON"
                            + " mt.manager_user_id=f.user_id AND mt.building_id=l.to_building_id"
                            + " WHERE f.user_id=? AND l.quality='CONFIRMED' AND l.created_at>?",
                        (row, index) ->
                                new MobilityModels.Efficiency(
                                        row.getInt("sample_count"),
                                        row.getInt("measured_count"),
                                        decimal(row, "mean_error"),
                                        decimal(row, "predicted_minutes"),
                                        decimal(row, "actual_minutes"),
                                        0,
                                        ""),
                        id(actor.id()),
                        ts(clock.instant().minusSeconds(30L * 86400)));
        var sample = rows.getFirst();
        var baseline = new HashMap<String, Integer>();
        repository.query(
                "SELECT from_building_id,to_building_id,minutes FROM dispatch_travel",
                (row, index) -> {
                    baseline.put(row.getString(1) + ":" + row.getString(2), row.getInt(3));
                    return row.getInt(3);
                });
        int models =
                personalMinutes(minutes(baseline), actor.id())
                        .getOrDefault(actor.id(), Map.of())
                        .size();
        return new MobilityModels.Efficiency(
                sample.sampleCount(),
                sample.measuredCount(),
                sample.meanAbsoluteErrorMinutes(),
                sample.predictedMinutes(),
                sample.actualMinutes(),
                models,
                "本人近30天双端确认入口间的有效片段；预测误差不等同路线节省。个人模型至少5段、3个工作会话，通行估计限共享基线0.75至1.5倍，失效即回退。");
    }

    java.util.List<MobilityModels.TravelModel> visibleModels(Actor actor) {
        return repository.query(
                "SELECT t.* FROM mobility_travel_model t JOIN manager_building mf ON"
                        + " mf.building_id=t.from_building_id JOIN manager_building mt ON"
                        + " mt.building_id=t.to_building_id WHERE mf.manager_user_id=? AND"
                        + " mt.manager_user_id=? ORDER BY t.from_building_id,t.to_building_id LIMIT"
                        + " 500",
                (row, index) ->
                        new MobilityModels.TravelModel(
                                row.getString("from_building_id"),
                                row.getString("to_building_id"),
                                row.getInt("minutes"),
                                row.getInt("sample_count"),
                                row.getInt("session_count"),
                                row.getBoolean("enabled"),
                                row.getInt("version"),
                                time(row, "updated_at")),
                id(actor.id()),
                id(actor.id()));
    }

    void rollback(Actor actor, String from, String to, int expectedVersion) {
        int scopes =
                repository
                        .query(
                                "SELECT building_id FROM manager_building WHERE manager_user_id=?"
                                        + " AND building_id IN (?,?)",
                                (row, index) -> row.getString(1),
                                id(actor.id()),
                                id(from),
                                id(to))
                        .size();
        if (scopes != (from.equals(to) ? 1 : 2)) {
            throw ApiException.missing();
        }
        ModelRow previous = model(from, to);
        if (previous == null || previous.version() != expectedVersion) {
            throw ApiException.conflict("通行模型版本已变化");
        }
        repository.update(
                "UPDATE mobility_travel_model SET enabled=FALSE,version=version+1,updated_at=? "
                        + "WHERE from_building_id=? AND to_building_id=?",
                ts(clock.instant()),
                id(from),
                id(to));
        revision(
                from,
                to,
                expectedVersion + 1,
                previous.minutes(),
                false,
                previous.samples(),
                previous.sessions(),
                "MANUAL_ROLLBACK_TO_BASELINE");
    }

    private void revision(
            String from,
            String to,
            int version,
            int minutes,
            boolean enabled,
            int samples,
            int sessions,
            String reason) {
        repository.update(
                "INSERT INTO"
                    + " mobility_model_revision(from_building_id,to_building_id,version,minutes,enabled,sample_count,session_count,reason,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                id(from),
                id(to),
                version,
                minutes,
                enabled,
                samples,
                sessions,
                reason,
                ts(clock.instant()));
    }
}
