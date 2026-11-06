package cn.dormlink;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class StatisticsService {
  final Db db;
  final BusinessSupport ops;

  StatisticsService(Db d, BusinessSupport o) {
    db = d;
    ops = o;
  }

  String scope(String alias, Map<String, String> q, List<Object> args) {
    String s = ops.scope(alias + ".building_id");
    String id = q.get("buildingId");
    if (id != null && !id.isBlank()) {
      s += " AND " + alias + ".building_id=?";
      args.add(id);
    }
    return s;
  }

  Object overview(Map<String, String> q) {
    var args = new ArrayList<Object>();
    String s = scope("r", q, args);
    var rooms =
        db.rows(
            "SELECT r.capacity,(SELECT COUNT(*) FROM residence h WHERE h.room_id=r.id AND"
                + " h.checked_out_at IS NULL) occupied_count FROM dorm_room r JOIN building b ON"
                + " b.id=r.building_id WHERE r.enabled=TRUE AND b.enabled=TRUE AND "
                + s,
            args.toArray());
    long cap = rooms.stream().mapToLong(x -> Db.number(x, "capacity")).sum(),
        occupied = rooms.stream().mapToLong(x -> Db.number(x, "occupiedCount")).sum();
    List<Map<String, Object>> repairs = repairRows(q, false),
        hygiene = hygieneRows(q, false),
        visitors = visitorRows(q, false);
    long
        open =
            repairs.stream()
                .filter(
                    x ->
                        Set.of("SUBMITTED", "ACCEPTED", "PROCESSING", "PENDING_CONFIRMATION")
                            .contains(x.get("status")))
                .count(),
        pending =
            repairs.stream().filter(x -> "PENDING_CONFIRMATION".equals(x.get("status"))).count(),
        visiting = visitors.stream().filter(x -> x.get("leftAt") == null).count();
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    long todayVisits =
        visitors.stream().filter(x -> date(x.get("arrivedAt")).equals(today.toString())).count();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("roomCount", rooms.size());
    out.put("capacity", cap);
    out.put("occupiedCount", occupied);
    out.put("availableCount", cap - occupied);
    out.put("occupancyRate", cap == 0 ? 0 : Math.round(10000.0 * occupied / cap) / 100.0);
    out.put("openRepairs", open);
    out.put("pendingConfirmation", pending);
    out.put("visitingCount", visiting);
    out.put("todayVisitors", todayVisits);
    out.put("hygieneAverage", average(hygiene, "totalScore"));
    out.put("rectifyRoomCount", rectify(hygiene));
    return out;
  }

  List<Map<String, Object>> repairRows(Map<String, String> q, boolean range) {
    var args = new ArrayList<Object>();
    String sql =
        "SELECT o.status,o.category,o.submitted_at,o.completed_at FROM repair_order o WHERE "
            + scope("o", q, args);
    if (range) sql = BusinessSupport.dates(sql, args, q, "o.submitted_at");
    return db.rows(sql, args.toArray());
  }

  List<Map<String, Object>> hygieneRows(Map<String, String> q, boolean range) {
    var args = new ArrayList<Object>();
    String sql =
        "SELECT h.id,h.room_id,h.total_score,h.grade,h.inspected_at FROM hygiene_inspection h WHERE"
            + " h.voided_at IS NULL AND "
            + scope("h", q, args);
    if (range) sql = BusinessSupport.dates(sql, args, q, "h.inspected_at");
    return db.rows(sql + " ORDER BY h.inspected_at,h.id", args.toArray());
  }

  List<Map<String, Object>> visitorRows(Map<String, String> q, boolean range) {
    var args = new ArrayList<Object>();
    String sql =
        "SELECT v.arrived_at,v.left_at FROM visitor_registration v WHERE " + scope("v", q, args);
    if (range) sql = BusinessSupport.dates(sql, args, q, "v.arrived_at");
    return db.rows(sql, args.toArray());
  }

  Object repairs(Map<String, String> q) {
    var rows = repairRows(q, true);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "byStatus",
        distribution(
            rows,
            "status",
            List.of(
                "SUBMITTED",
                "ACCEPTED",
                "PROCESSING",
                "PENDING_CONFIRMATION",
                "COMPLETED",
                "CANCELLED",
                "REJECTED")));
    out.put(
        "byCategory",
        distribution(rows, "category", List.of("UTILITIES", "FURNITURE", "NETWORK", "OTHER")));
    out.put("trend", countTrend(rows, "submittedAt"));
    var done = rows.stream().filter(r -> r.get("completedAt") != null).toList();
    out.put(
        "averageHours",
        done.isEmpty()
            ? null
            : done.stream()
                .mapToDouble(
                    r ->
                        Duration.between(
                                    Instant.parse(r.get("submittedAt").toString()),
                                    Instant.parse(r.get("completedAt").toString()))
                                .toMillis()
                            / 3600000.0)
                .average()
                .orElse(0));
    out.put("total", rows.size());
    out.put("completed", done.size());
    return out;
  }

  Object hygiene(Map<String, String> q) {
    var rows = hygieneRows(q, true);
    Map<String, List<Map<String, Object>>> grouped =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    r -> date(r.get("inspectedAt")), TreeMap::new, Collectors.toList()));
    var trend =
        grouped.entrySet().stream()
            .map(
                e ->
                    Map.of(
                        "date",
                        e.getKey(),
                        "average",
                        Objects.requireNonNull(average(e.getValue(), "totalScore"))))
            .toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("average", average(rows, "totalScore"));
    out.put(
        "byGrade", distribution(rows, "grade", List.of("EXCELLENT", "GOOD", "PASS", "RECTIFY")));
    out.put("trend", trend);
    out.put("rectifyRoomCount", rectify(hygieneRows(q, false)));
    return out;
  }

  Object visitors(Map<String, String> q) {
    var rows = visitorRows(q, true);
    return Map.of(
        "visitingCount",
        visitorRows(q, false).stream().filter(r -> r.get("leftAt") == null).count(),
        "total",
        rows.size(),
        "trend",
        countTrend(rows, "arrivedAt"));
  }

  static String date(Object t) {
    return Instant.parse(t.toString()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
  }

  static Double average(List<Map<String, Object>> rows, String k) {
    return rows.isEmpty()
        ? null
        : Math.round(
                rows.stream()
                        .mapToDouble(r -> Double.parseDouble(r.get(k).toString()))
                        .average()
                        .orElse(0)
                    * 100)
            / 100.0;
  }

  static long rectify(List<Map<String, Object>> rows) {
    Map<Object, Map<String, Object>> latest = new HashMap<>();
    for (var row : rows) latest.put(row.get("roomId"), row);
    return latest.values().stream().filter(r -> "RECTIFY".equals(r.get("grade"))).count();
  }

  static List<Map<String, Object>> distribution(
      List<Map<String, Object>> rows, String k, List<String> values) {
    return values.stream()
        .map(
            value ->
                Map.<String, Object>of(
                    "name",
                    value,
                    "value",
                    rows.stream().filter(r -> value.equals(r.get(k))).count()))
        .toList();
  }

  static List<Map<String, Object>> countTrend(List<Map<String, Object>> rows, String key) {
    Map<String, Long> grouped =
        rows.stream()
            .collect(
                Collectors.groupingBy(r -> date(r.get(key)), TreeMap::new, Collectors.counting()));
    return grouped.entrySet().stream()
        .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue()))
        .toList();
  }
}
