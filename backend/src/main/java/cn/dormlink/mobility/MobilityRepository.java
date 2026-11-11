package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All device SQL is bounded and typed. No generic business-command/user lock is used. */
@Repository
class MobilityRepository {
    final JdbcTemplate jdbc;
    MobilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Work(Session view, String authSessionId, String clientInstanceId, long lastSequence,
            String streamId, Double elapsedMs) {}
    record Position(Context view, String anchorId, Instant anchorConfirmedAt) {}

    Work work(String id, boolean lock) {
        return first(query("SELECT * FROM field_work_session WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                this::workRow, id));
    }
    Work current(String userId, boolean lock) {
        return first(query("SELECT * FROM field_work_session WHERE user_id=? "
                + "AND state IN ('ACTIVE','PAUSED') ORDER BY started_at DESC,source_epoch DESC LIMIT 1"
                + (lock ? " FOR UPDATE" : ""), this::workRow, id(userId)));
    }
    Work latest(String userId) {
        return first(query("SELECT * FROM field_work_session WHERE user_id=? "
                + "ORDER BY started_at DESC,source_epoch DESC LIMIT 1", this::workRow, id(userId)));
    }
    private Work workRow(ResultSet r, int index) throws SQLException {
        return new Work(new Session(r.getString("id"), r.getString("user_id"), r.getString("state"),
                r.getInt("source_epoch"), time(r,"lease_expires_at"), time(r,"started_at"),
                time(r,"last_sample_at"), r.getBoolean("background_requested"), r.getInt("version")),
                r.getString("auth_session_id"), r.getString("client_instance_id"),
                r.getLong("last_sequence"), r.getString("stream_id"), decimal(r,"last_elapsed_ms"));
    }
    Position position(String userId) {
        return first(query("SELECT c.*,b.name AS building_name FROM manager_context_snapshot c "
                + "LEFT JOIN building b ON b.id=c.building_id WHERE c.user_id=?", this::positionRow, id(userId)));
    }
    Position positionRow(ResultSet r, int index) throws SQLException {
        return new Position(new Context(r.getString("user_id"),r.getString("session_id"), "VALID",
                r.getString("source"), r.getString("building_id"), r.getString("building_name"),
                integer(r,"floor_no"), decimal(r,"latitude"), decimal(r,"longitude"),
                decimal(r,"accuracy_meters"),decimal(r,"speed_mps"),r.getString("motion"),
                r.getString("quality"),time(r,"observed_at"),time(r,"expires_at"),
                r.getInt("context_version"),r.getString("explanation")),
                r.getString("anchor_id"),time(r,"anchor_confirmed_at"));
    }
    List<Anchor> anchors(String userId) {
        return query("SELECT a.*,b.name AS building_name FROM campus_anchor a "
                + "JOIN building b ON b.id=a.building_id JOIN manager_building m ON m.building_id=a.building_id "
                + "WHERE m.manager_user_id=? AND b.enabled=TRUE ORDER BY b.code,a.name LIMIT 500",
                this::anchorRow, id(userId));
    }
    Anchor anchor(String anchorId, String userId) {
        return first(query("SELECT a.*,b.name AS building_name FROM campus_anchor a "
                + "JOIN building b ON b.id=a.building_id JOIN manager_building m ON m.building_id=a.building_id "
                + "WHERE a.id=? AND m.manager_user_id=? AND b.enabled=TRUE",this::anchorRow,anchorId,id(userId)));
    }
    Anchor anchorRow(ResultSet r, int index) throws SQLException {
        return new Anchor(r.getString("id"),r.getString("building_id"),r.getString("building_name"),
                integer(r,"floor_no"),r.getString("name"),r.getString("scan_code"),
                decimal(r,"latitude"),decimal(r,"longitude"),decimal(r,"radius_meters"),
                r.getBoolean("enabled"),r.getInt("version"));
    }
    <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        return jdbc.query(sql, statement -> bind(statement, values), mapper);
    }
    void batchUpdate(String sql,List<Object[]> values) {
        jdbc.batchUpdate(sql,values);
    }
    int update(String sql, Object... values) {
        return jdbc.update(sql, statement -> bind(statement, values));
    }
    private void bind(java.sql.PreparedStatement statement, Object[] values) throws SQLException {
        var utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        for (int index = 0; index < values.length; index++) {
            if (values[index] instanceof Timestamp time) { statement.setTimestamp(index + 1, time, utc); }
            else { statement.setObject(index + 1, values[index]); }
        }
    }
    static long id(String value) {
        try {
            long parsed=Long.parseLong(value);
            if (parsed<1) { throw new NumberFormatException("Non-positive ID"); }
            return parsed;
        } catch (NumberFormatException exception) {
            throw cn.dormlink.shared.api.ApiException.bad("标识格式无效");
        }
    }
    static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant time(ResultSet row, String column) throws SQLException {
        Timestamp time = row.getTimestamp(column, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return time == null ? null : time.toInstant();
    }
    static Double decimal(ResultSet row,String column) throws SQLException {
        double value=row.getDouble(column); return row.wasNull() ? null : value;
    }
    static Integer integer(ResultSet row,String column) throws SQLException {
        int value=row.getInt(column); return row.wasNull() ? null : value;
    }
    static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}
