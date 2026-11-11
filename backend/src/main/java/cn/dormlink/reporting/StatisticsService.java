package cn.dormlink.reporting;

import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.application.BusinessSupport;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Authorized database aggregates; historical business rows stay in the database. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StatisticsService {
    final Db db;
    final BusinessSupport ops;

    StatisticsService(Db db, BusinessSupport ops) {
        this.db = db;
        this.ops = ops;
    }

    private Query query(String table, String alias, Map<String, String> filters, String time, boolean range) {
        var args = new ArrayList<Object>();
        String sql = " FROM " + table + " " + alias + " WHERE " + ops.scope(alias + ".building_id");
        sql = BusinessSupport.filter(sql, args, filters, "buildingId", alias + ".building_id");
        if (range) sql = BusinessSupport.dates(sql, args, filters, alias + "." + time);
        return new Query(sql, args.toArray());
    }

    public Object overview(Map<String, String> filters) {
        Query rooms = query("dorm_room", "r", filters, null, false);
        var capacity = db.one("SELECT COUNT(*) room_count,COALESCE(SUM(r.capacity),0) capacity,"
                + "COALESCE(SUM((SELECT COUNT(*) FROM residence h WHERE h.room_id=r.id AND h.checked_out_at IS NULL)),0) occupied_count"
                + rooms.sql() + " AND r.enabled=TRUE AND EXISTS(SELECT 1 FROM building b WHERE b.id=r.building_id AND b.enabled=TRUE)", rooms.args());
        Query repairs = query("repair_order", "o", filters, null, false);
        var repair = db.one("SELECT COALESCE(SUM(CASE WHEN status IN ('SUBMITTED','ACCEPTED','PROCESSING','PENDING_CONFIRMATION') THEN 1 ELSE 0 END),0) open_repairs,"
                + "COALESCE(SUM(CASE WHEN status='PENDING_CONFIRMATION' THEN 1 ELSE 0 END),0) pending_confirmation" + repairs.sql(), repairs.args());
        Query visitors = query("visitor_registration", "v", filters, null, false);
        var args = new ArrayList<Object>();
        Instant today = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        args.add(Timestamp.from(today));
        args.add(Timestamp.from(today.plusSeconds(86400)));
        args.addAll(Arrays.asList(visitors.args()));
        var visiting = db.one("SELECT COALESCE(SUM(CASE WHEN left_at IS NULL THEN 1 ELSE 0 END),0) visiting_count,"
                + "COALESCE(SUM(CASE WHEN arrived_at>=? AND arrived_at<? THEN 1 ELSE 0 END),0) today_visitors" + visitors.sql(), args.toArray());
        Query hygiene = query("hygiene_inspection", "h", filters, null, false);
        var hygieneAverage = db.one("SELECT AVG(total_score) average" + hygiene.sql() + " AND h.voided_at IS NULL", hygiene.args());
        long cap = Db.number(capacity, "capacity"), occupied = Db.number(capacity, "occupiedCount");
        var result = new LinkedHashMap<String, Object>();
        result.put("roomCount", Db.number(capacity, "roomCount"));
        result.put("capacity", cap);
        result.put("occupiedCount", occupied);
        result.put("availableCount", cap - occupied);
        result.put("occupancyRate", cap == 0 ? 0 : Math.round(10000.0 * occupied / cap) / 100.0);
        result.put("openRepairs", Db.number(repair, "openRepairs"));
        result.put("pendingConfirmation", Db.number(repair, "pendingConfirmation"));
        result.put("visitingCount", Db.number(visiting, "visitingCount"));
        result.put("todayVisitors", Db.number(visiting, "todayVisitors"));
        result.put("hygieneAverage", rounded(hygieneAverage.get("average")));
        result.put("rectifyRoomCount", rectify(filters));
        return result;
    }

    public Object repairs(Map<String, String> filters) {
        Query query = query("repair_order", "o", filters, "submitted_at", true);
        var totals = db.one("SELECT COUNT(*) total,COUNT(completed_at) completed,AVG(" + db.durationHours("submitted_at", "completed_at")
                + ") average_hours" + query.sql(), query.args());
        var result = new LinkedHashMap<String, Object>();
        result.put("byStatus", distribution(query, "status", List.of("SUBMITTED", "ACCEPTED", "PROCESSING", "PENDING_CONFIRMATION", "COMPLETED", "CANCELLED", "REJECTED")));
        result.put("byCategory", distribution(query, "category", List.of("UTILITIES", "FURNITURE", "NETWORK", "OTHER")));
        result.put("trend", countTrend(query, "submitted_at"));
        result.put("averageHours", totals.get("averageHours") == null ? null : ((Number) totals.get("averageHours")).doubleValue());
        result.put("total", Db.number(totals, "total"));
        result.put("completed", Db.number(totals, "completed"));
        return result;
    }

    public Object hygiene(Map<String, String> filters) {
        Query base = query("hygiene_inspection", "h", filters, "inspected_at", true);
        Query query = new Query(base.sql() + " AND h.voided_at IS NULL", base.args());
        var summary = db.one("SELECT AVG(total_score) average" + query.sql(), query.args());
        String day = db.chinaDate("inspected_at");
        var trend = db.rows("SELECT " + day + " AS \"date\",AVG(total_score) average" + query.sql() + " GROUP BY " + day + " ORDER BY " + day, query.args());
        trend.forEach(row -> row.put("average", rounded(row.get("average"))));
        var result = new LinkedHashMap<String, Object>();
        result.put("average", rounded(summary.get("average")));
        result.put("byGrade", distribution(query, "grade", List.of("EXCELLENT", "GOOD", "PASS", "RECTIFY")));
        result.put("trend", trend);
        result.put("rectifyRoomCount", rectify(filters));
        return result;
    }

    public Object visitors(Map<String, String> filters) {
        Query range = query("visitor_registration", "v", filters, "arrived_at", true);
        Query all = query("visitor_registration", "v", filters, null, false);
        return Map.of("visitingCount", db.count("SELECT COUNT(*)" + all.sql() + " AND left_at IS NULL", all.args()),
                "total", db.count("SELECT COUNT(*)" + range.sql(), range.args()), "trend", countTrend(range, "arrived_at"));
    }

    private long rectify(Map<String, String> filters) {
        Query query = query("hygiene_inspection", "h", filters, null, false);
        return db.count("SELECT COUNT(*)" + query.sql() + " AND h.voided_at IS NULL AND h.grade='RECTIFY'"
                + " AND NOT EXISTS(SELECT 1 FROM hygiene_inspection newer WHERE newer.room_id=h.room_id"
                + " AND newer.voided_at IS NULL AND (newer.inspected_at>h.inspected_at OR"
                + " (newer.inspected_at=h.inspected_at AND newer.id>h.id)))", query.args());
    }

    private List<Map<String, Object>> distribution(Query query, String column, List<String> values) {
        var counts = new HashMap<String, Long>();
        for (var row : db.rows("SELECT " + column + " name,COUNT(*) value" + query.sql() + " GROUP BY " + column, query.args())) {
            counts.put(row.get("name").toString(), Db.number(row, "value"));
        }
        return values.stream().map(value -> Map.<String, Object>of("name", value, "value", counts.getOrDefault(value, 0L))).toList();
    }

    private List<Map<String, Object>> countTrend(Query query, String column) {
        String day = db.chinaDate(column);
        return db.rows("SELECT " + day + " AS \"date\",COUNT(*) count" + query.sql() + " GROUP BY " + day + " ORDER BY " + day, query.args());
    }

    private static Double rounded(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue() * 100) / 100.0;
    }

    private record Query(String sql, Object[] args) {}
}
