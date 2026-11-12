package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;

import cn.dormlink.identity.Actor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** All device SQL is bounded and typed. No generic business-command/user lock is used. */
@Repository
class MobilityRepository {
    private final JdbcTemplate jdbc;

    MobilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Work(
            Session view,
            String authSessionId,
            String clientInstanceId,
            long lastSequence,
            String streamId,
            Double elapsedMs) {}

    record Position(Context view, String anchorId, Instant anchorConfirmedAt) {}

    record Receipt(String requestHash, String ackJson) {}

    record PlanningPosition(
            Position position,
            Instant startedAt,
            Instant leaseExpiresAt,
            Instant authExpiresAt,
            boolean active,
            boolean validIdentity,
            boolean authorizedBuilding) {}

    Work work(String id, boolean lock) {
        return first(
                query(
                        "SELECT * FROM field_work_session WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        this::workRow,
                        id));
    }

    Work current(String userId, boolean lock) {
        return first(
                query(
                        "SELECT * FROM field_work_session WHERE user_id=? AND state IN"
                                + " ('ACTIVE','PAUSED') ORDER BY started_at DESC,source_epoch DESC"
                                + " LIMIT 1"
                                + (lock ? " FOR UPDATE" : ""),
                        this::workRow,
                        id(userId)));
    }

    Work latest(String userId) {
        return first(
                query(
                        "SELECT * FROM field_work_session WHERE user_id=? "
                                + "ORDER BY started_at DESC,source_epoch DESC LIMIT 1",
                        this::workRow,
                        id(userId)));
    }

    private Work workRow(ResultSet r, int index) throws SQLException {
        return new Work(
                new Session(
                        r.getString("id"),
                        r.getString("user_id"),
                        r.getString("state"),
                        r.getInt("source_epoch"),
                        time(r, "lease_expires_at"),
                        time(r, "started_at"),
                        time(r, "last_sample_at"),
                        r.getBoolean("background_requested"),
                        r.getInt("version")),
                r.getString("auth_session_id"),
                r.getString("client_instance_id"),
                r.getLong("last_sequence"),
                r.getString("stream_id"),
                decimal(r, "last_elapsed_ms"));
    }

    Position position(String userId) {
        return first(
                query(
                        "SELECT c.*,b.name AS building_name FROM manager_context_snapshot c "
                                + "LEFT JOIN building b ON b.id=c.building_id WHERE c.user_id=?",
                        this::positionRow,
                        id(userId)));
    }

    private Position positionRow(ResultSet r, int index) throws SQLException {
        return new Position(
                new Context(
                        r.getString("user_id"),
                        r.getString("session_id"),
                        "VALID",
                        r.getString("source"),
                        r.getString("building_id"),
                        r.getString("building_name"),
                        integer(r, "floor_no"),
                        decimal(r, "latitude"),
                        decimal(r, "longitude"),
                        decimal(r, "accuracy_meters"),
                        decimal(r, "speed_mps"),
                        r.getString("motion"),
                        r.getString("quality"),
                        time(r, "observed_at"),
                        time(r, "expires_at"),
                        r.getInt("context_version"),
                        r.getString("explanation")),
                r.getString("anchor_id"),
                time(r, "anchor_confirmed_at"));
    }

    List<Anchor> anchors(String userId) {
        return query(
                "SELECT a.*,b.name AS building_name FROM campus_anchor a JOIN building b ON"
                    + " b.id=a.building_id JOIN manager_building m ON m.building_id=a.building_id"
                    + " WHERE m.manager_user_id=? AND b.enabled=TRUE ORDER BY b.code,a.name LIMIT"
                    + " 500",
                this::anchorRow,
                id(userId));
    }

    Anchor anchor(String anchorId, String userId) {
        return first(
                query(
                        "SELECT a.*,b.name AS building_name FROM campus_anchor a JOIN building b ON"
                            + " b.id=a.building_id JOIN manager_building m ON"
                            + " m.building_id=a.building_id WHERE a.id=? AND m.manager_user_id=?"
                            + " AND b.enabled=TRUE",
                        this::anchorRow,
                        anchorId,
                        id(userId)));
    }

    private Anchor anchorRow(ResultSet r, int index) throws SQLException {
        return new Anchor(
                r.getString("id"),
                r.getString("building_id"),
                r.getString("building_name"),
                integer(r, "floor_no"),
                r.getString("name"),
                r.getString("scan_code"),
                decimal(r, "latitude"),
                decimal(r, "longitude"),
                decimal(r, "radius_meters"),
                r.getBoolean("enabled"),
                r.getInt("version"));
    }

    boolean validLogin(String sessionId, String userId, Instant now) {
        return !query(
                        "SELECT a.id FROM auth_session a JOIN sys_user u ON u.id=a.user_id"
                                + " WHERE a.id=? AND a.revoked_at IS NULL AND a.expires_at>?"
                                + " AND u.enabled=TRUE AND u.role='MANAGER'"
                                + (userId == null ? "" : " AND a.user_id=?"),
                        (row, index) -> row.getLong(1),
                        userId == null
                                ? new Object[] {id(sessionId), ts(now)}
                                : new Object[] {id(sessionId), ts(now), id(userId)})
                .isEmpty();
    }

    void createWork(String workId, Actor actor, Start request, int epoch, Instant now) {
        update(
                "INSERT INTO"
                    + " field_work_session(id,user_id,active_user_id,auth_session_id,client_instance_id,source_epoch,state,consent_version,background_requested,lease_expires_at,started_at)"
                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,?)",
                workId,
                id(actor.id()),
                id(actor.id()),
                id(actor.sessionId()),
                request.clientInstanceId(),
                epoch,
                request.consentVersion(),
                Boolean.TRUE.equals(request.backgroundRequested()),
                ts(now.plusSeconds(POLICY.leaseSeconds())),
                ts(now));
    }

    void attachContext(String userId, String workId) {
        if (position(userId) == null) {
            update(
                    "INSERT INTO manager_context_snapshot(user_id,session_id,source,explanation)"
                            + " VALUES(?,?,'NONE',?)",
                    id(userId),
                    workId,
                    "尚无有效观测，请确认现场锚点");
        } else {
            update(
                    "UPDATE manager_context_snapshot SET"
                            + " session_id=?,context_version=context_version+1 WHERE user_id=?",
                    workId,
                    id(userId));
        }
    }

    void pause(String workId) {
        update("UPDATE field_work_session SET state='PAUSED',version=version+1 WHERE id=?", workId);
    }

    void resume(String workId, Instant leaseExpiresAt) {
        update(
                "UPDATE field_work_session SET"
                    + " state='ACTIVE',source_epoch=source_epoch+1,last_sequence=0,stream_id=NULL,last_elapsed_ms=NULL,lease_expires_at=?,version=version+1"
                    + " WHERE id=?",
                ts(leaseExpiresAt),
                workId);
    }

    void endWork(String workId, String state, Instant endedAt) {
        update(
                "UPDATE field_work_session SET"
                        + " state=?,active_user_id=NULL,ended_at=?,version=version+1 WHERE id=?",
                state,
                ts(endedAt),
                workId);
    }

    void clearContext(String userId, String workId) {
        update(
                "UPDATE manager_context_snapshot SET"
                    + " source='NONE',building_id=NULL,floor_no=NULL,latitude=NULL,"
                    + "longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,motion='UNKNOWN',quality='LOW',expires_at=NULL,anchor_id=NULL,anchor_confirmed_at=NULL,context_version=context_version+1"
                    + " WHERE user_id=? AND session_id=?",
                id(userId),
                workId);
        update(
                "DELETE FROM mobility_pending_leg WHERE user_id=? AND session_id=?",
                id(userId),
                workId);
    }

    Receipt receipt(String workId, String batchId) {
        return first(
                query(
                        "SELECT request_hash,ack_json FROM mobility_batch_receipt WHERE"
                                + " session_id=? AND batch_id=?",
                        (row, index) -> new Receipt(row.getString(1), row.getString(2)),
                        workId,
                        batchId));
    }

    void observationReceived(
            String workId,
            long sequence,
            Sample latest,
            Instant receivedAt,
            Instant leaseExpiresAt) {
        if (latest == null) {
            update(
                    "UPDATE field_work_session SET last_sequence=?,last_sample_at=? WHERE id=?",
                    sequence,
                    ts(receivedAt),
                    workId);
        } else {
            update(
                    "UPDATE field_work_session SET"
                        + " last_sequence=?,stream_id=?,last_elapsed_ms=?,last_sample_at=?,lease_expires_at=?"
                        + " WHERE id=?",
                    sequence,
                    latest.streamId(),
                    latest.elapsedMs(),
                    ts(receivedAt),
                    ts(leaseExpiresAt),
                    workId);
        }
    }

    void saveReceipt(
            String workId, String batchId, String requestHash, String ackJson, Instant now) {
        update(
                "INSERT INTO"
                    + " mobility_batch_receipt(session_id,batch_id,request_hash,ack_json,created_at)"
                    + " VALUES(?,?,?,?,?)",
                workId,
                batchId,
                requestHash,
                ackJson,
                ts(now));
    }

    void confirmPosition(String userId, Anchor anchor, String source, Instant now) {
        update(
                "UPDATE manager_context_snapshot SET source=?,building_id=?,floor_no=?,"
                    + "latitude=NULL,longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,motion='UNKNOWN',quality='MEDIUM',"
                    + "observed_at=?,expires_at=?,anchor_id=?,anchor_confirmed_at=?,context_version=context_version+1,explanation=?"
                    + " WHERE user_id=?",
                source,
                id(anchor.buildingId()),
                anchor.floorNo(),
                ts(now),
                ts(now.plusSeconds(POLICY.leaseSeconds())),
                anchor.id(),
                ts(now),
                "已由本人确认锚点；静态二维码不证明物理到场",
                id(userId));
    }

    void renewLease(String workId, Instant expiresAt) {
        update(
                "UPDATE field_work_session SET lease_expires_at=? WHERE id=?",
                ts(expiresAt),
                workId);
    }

    List<Anchor> studentAnchors(String userId) {
        return query(
                "SELECT a.*,b.name AS building_name FROM campus_anchor a JOIN building b ON"
                    + " b.id=a.building_id JOIN residence r ON r.building_id=a.building_id JOIN"
                    + " student s ON s.id=r.student_id WHERE s.user_id=? AND r.checked_out_at IS"
                    + " NULL AND a.enabled=TRUE AND b.enabled=TRUE ORDER BY a.name LIMIT 500",
                this::anchorRow,
                id(userId));
    }

    Integer managedFloorCount(String userId, String buildingId) {
        return first(
                query(
                        "SELECT b.floor_count FROM building b JOIN manager_building m ON"
                                + " m.building_id=b.id WHERE b.id=? AND m.manager_user_id=? AND"
                                + " b.enabled=TRUE",
                        (row, index) -> row.getInt(1),
                        id(buildingId),
                        id(userId)));
    }

    void createAnchor(String anchorId, String scanCode, AnchorWrite request) {
        update(
                "INSERT INTO"
                    + " campus_anchor(id,building_id,floor_no,name,scan_code,latitude,longitude,radius_meters,enabled)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                anchorId,
                id(request.buildingId()),
                request.floorNo(),
                request.name().trim(),
                scanCode,
                request.latitude(),
                request.longitude(),
                request.radiusMeters(),
                request.enabled() == null || request.enabled());
    }

    boolean updateAnchor(String anchorId, AnchorWrite request, boolean enabled) {
        return update(
                        "UPDATE campus_anchor SET"
                            + " building_id=?,floor_no=?,name=?,latitude=?,longitude=?,radius_meters=?,enabled=?,version=version+1"
                            + " WHERE id=? AND version=?",
                        id(request.buildingId()),
                        request.floorNo(),
                        request.name().trim(),
                        request.latitude(),
                        request.longitude(),
                        request.radiusMeters(),
                        enabled,
                        anchorId,
                        request.expectedVersion())
                == 1;
    }

    void invalidateAnchorContexts(String anchorId) {
        update(
                "UPDATE manager_context_snapshot SET"
                    + " building_id=NULL,floor_no=NULL,expires_at=NULL,context_version=context_version+1"
                    + " WHERE anchor_id=?",
                anchorId);
    }

    List<String> activeUserIds(Instant now) {
        return query(
                "SELECT DISTINCT f.user_id FROM field_work_session f JOIN auth_session a ON"
                        + " a.id=f.auth_session_id JOIN sys_user u ON u.id=f.user_id WHERE"
                        + " f.state='ACTIVE' AND f.lease_expires_at>? AND a.revoked_at IS NULL"
                        + " AND a.expires_at>? AND u.enabled=TRUE AND u.role='MANAGER'",
                (row, index) -> row.getString(1),
                ts(now),
                ts(now));
    }

    List<PlanningPosition> planningPositions() {
        return query(
                "SELECT c.*,f.state,f.started_at,f.lease_expires_at,a.revoked_at,a.expires_at AS"
                    + " auth_expires_at,u.enabled AS user_enabled,u.role,b.enabled AS"
                    + " building_enabled,b.name AS building_name,m.building_id AS"
                    + " authorized_building FROM manager_context_snapshot c JOIN field_work_session"
                    + " f ON f.id=c.session_id JOIN auth_session a ON a.id=f.auth_session_id JOIN"
                    + " sys_user u ON u.id=f.user_id LEFT JOIN building b ON b.id=c.building_id"
                    + " LEFT JOIN manager_building m ON m.manager_user_id=c.user_id AND"
                    + " m.building_id=c.building_id",
                (row, index) ->
                        new PlanningPosition(
                                positionRow(row, index),
                                time(row, "started_at"),
                                time(row, "lease_expires_at"),
                                time(row, "auth_expires_at"),
                                "ACTIVE".equals(row.getString("state")),
                                time(row, "revoked_at") == null
                                        && row.getBoolean("user_enabled")
                                        && "MANAGER".equals(row.getString("role")),
                                row.getBoolean("building_enabled")
                                        && row.getString("authorized_building") != null));
    }

    boolean hasBuildingScope(String userId, String buildingId) {
        return !query(
                        "SELECT building_id FROM manager_building WHERE manager_user_id=? AND"
                                + " building_id=?",
                        (row, index) -> row.getString(1),
                        id(userId),
                        id(buildingId))
                .isEmpty();
    }

    void savePosition(
            String userId,
            String source,
            String buildingId,
            Integer floorNo,
            Sample sample,
            MobilityEstimator.Estimate estimate) {
        update(
                "UPDATE manager_context_snapshot SET"
                    + " source=?,building_id=?,floor_no=?,latitude=?,longitude=?,"
                    + "accuracy_meters=?,speed_mps=?,motion=?,quality=?,observed_at=?,expires_at=?,context_version=context_version+1,explanation=?"
                    + " WHERE user_id=?",
                source,
                buildingId == null ? null : id(buildingId),
                floorNo,
                sample.latitude(),
                sample.longitude(),
                sample.accuracyMeters(),
                estimate.speedMps(),
                estimate.motion(),
                estimate.quality(),
                ts(sample.observedAt()),
                ts(sample.observedAt().plusSeconds(POLICY.leaseSeconds())),
                estimate.explanation(),
                id(userId));
    }

    List<Work> revokedWorks(String userId, String sessionId) {
        return query(
                "SELECT f.* FROM field_work_session f JOIN auth_session a ON a.id=f.auth_session_id"
                        + " WHERE f.user_id=? AND f.state IN ('ACTIVE','PAUSED')"
                        + (sessionId == null
                                ? " AND a.revoked_at IS NOT NULL"
                                : " AND f.auth_session_id=?"),
                this::workRow,
                sessionId == null
                        ? new Object[] {id(userId)}
                        : new Object[] {id(userId), id(sessionId)});
    }

    List<String> expiredWorkIds(Instant now) {
        return query(
                "SELECT f.id FROM field_work_session f JOIN auth_session a ON"
                    + " a.id=f.auth_session_id JOIN sys_user u ON u.id=f.user_id WHERE f.state IN"
                    + " ('ACTIVE','PAUSED') AND (f.lease_expires_at<=? OR a.revoked_at IS NOT NULL"
                    + " OR a.expires_at<=? OR u.enabled=FALSE OR u.role<>'MANAGER') LIMIT 500",
                (row, index) -> row.getString(1),
                ts(now),
                ts(now));
    }

    void purgeExpiredData(Instant now) {
        update(
                "UPDATE manager_context_snapshot SET"
                        + " latitude=NULL,longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,"
                        + "building_id=NULL,floor_no=NULL,quality='LOW',motion='UNKNOWN' WHERE"
                        + " expires_at<=? AND (latitude IS NOT NULL OR building_id IS NOT NULL)",
                ts(now));
        var receipts =
                query(
                        "SELECT session_id,batch_id FROM mobility_batch_receipt WHERE created_at<?"
                                + " ORDER BY created_at LIMIT 500",
                        (row, index) -> new Object[] {row.getString(1), row.getString(2)},
                        ts(now.minusSeconds(86400)));
        batchUpdate(
                "DELETE FROM mobility_batch_receipt WHERE session_id=? AND batch_id=?", receipts);
        var legs =
                query(
                        "SELECT id FROM travel_leg_summary WHERE created_at<? ORDER BY created_at"
                                + " LIMIT 500",
                        (row, index) -> new Object[] {row.getString(1)},
                        ts(now.minusSeconds(30L * 86400)));
        batchUpdate("DELETE FROM travel_leg_summary WHERE id=?", legs);
        update("DELETE FROM mobility_pending_leg WHERE departed_at<?", ts(now.minusSeconds(1800)));
    }

    <T> List<T> query(
            String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        return jdbc.query(sql, statement -> bind(statement, values), mapper);
    }

    void batchUpdate(String sql, List<Object[]> values) {
        jdbc.batchUpdate(sql, values);
    }

    int update(String sql, Object... values) {
        return jdbc.update(sql, statement -> bind(statement, values));
    }

    private void bind(java.sql.PreparedStatement statement, Object[] values) throws SQLException {
        var utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        for (int index = 0; index < values.length; index++) {
            if (values[index] instanceof Timestamp time) {
                statement.setTimestamp(index + 1, time, utc);
            } else {
                statement.setObject(index + 1, values[index]);
            }
        }
    }

    static long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException("Non-positive ID");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw cn.dormlink.shared.api.ApiException.bad("标识格式无效");
        }
    }

    static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    static Instant time(ResultSet row, String column) throws SQLException {
        Timestamp time =
                row.getTimestamp(
                        column,
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return time == null ? null : time.toInstant();
    }

    static Double decimal(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    static Integer integer(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
