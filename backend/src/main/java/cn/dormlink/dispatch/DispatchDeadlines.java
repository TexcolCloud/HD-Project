package cn.dormlink.dispatch;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Time-only changes need a bounded trigger even when no device or person sends a new event. */
final class DispatchDeadlines {
    private Map<String, Instant> scheduled = Map.of();
    private final Set<String> consumed = new HashSet<>();
    private Instant earliestCheck;

    static Instant next(
            RoutePlanner.Result plan, List<Map<String, Object>> orders, Instant generatedAt) {
        return new DispatchDeadlines().planned(plan, orders, generatedAt);
    }

    synchronized Instant planned(
            RoutePlanner.Result plan, List<Map<String, Object>> orders, Instant generatedAt) {
        var boundaries = new HashMap<String, Instant>();
        Set<String> plannedIds = new HashSet<>();
        if (plan != null) {
            plan.routes()
                    .forEach(
                            route -> route.stops().forEach(stop -> plannedIds.add(stop.orderId())));
            plan.unassigned().forEach(task -> plannedIds.add(task.orderId()));
        }
        Map<String, Map<String, Object>> plannedOrders = new HashMap<>();
        for (var order : orders) {
            if (plannedIds.contains(order.get("id"))) {
                plannedOrders.put(order.get("id").toString(), order);
            }
        }
        // Use the same admitted jobs as the solver, not the first 500 raw rows (which may be
        // blocked).
        var batch = plan == null ? orders.stream().limit(500).toList() : plannedOrders.values();
        for (var order : batch) {
            for (String field : List.of("windowStart", "windowEnd")) {
                Instant boundary = instant(order.get(field));
                if (boundary != null) {
                    boundaries.put(key(order) + ":" + field, boundary);
                }
            }
        }
        if (plan != null) {
            for (var route : plan.routes()) {
                for (var stop : route.stops()) {
                    var order = plannedOrders.get(stop.orderId());
                    if (order == null) {
                        continue;
                    }
                    String state = Objects.toString(order.get("taskState"), "RECOMMENDED");
                    Instant boundary = state.equals("ON_SITE") ? stop.etaEnd() : stop.etaStart();
                    if (state.equals("ON_SITE") && order.get("taskUpdatedAt") != null) {
                        boundary =
                                instant(order.get("taskUpdatedAt"))
                                        .plusSeconds(
                                                ((Number) order.get("estimatedMinutes")).longValue()
                                                        * 60);
                    }
                    boundaries.put(key(order) + ":" + route.managerId() + ":EXECUTION", boundary);
                }
            }
        }
        consumed.retainAll(boundaries.keySet());
        scheduled = Map.copyOf(boundaries);
        earliestCheck = generatedAt.plusSeconds(60);
        return nextCheck();
    }

    synchronized Instant acknowledged(Instant now) {
        scheduled.forEach(
                (key, time) -> {
                    if (!time.isAfter(now)) {
                        consumed.add(key);
                    }
                });
        return nextCheck();
    }

    private Instant nextCheck() {
        Instant next = null;
        for (var entry : scheduled.entrySet()) {
            if (!consumed.contains(entry.getKey())) {
                Instant time = entry.getValue();
                next = earlier(next, time.isBefore(earliestCheck) ? earliestCheck : time);
            }
        }
        return next;
    }

    private static String key(Map<String, Object> order) {
        return order.get("id")
                + ":"
                + Objects.toString(order.get("version"), "0")
                + ":"
                + Objects.toString(order.get("taskVersion"), "0")
                + ":"
                + Objects.toString(order.get("taskState"), "RECOMMENDED");
    }

    private static Instant earlier(Instant one, Instant two) {
        return one == null || two.isBefore(one) ? two : one;
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }

    static String warning(Map<String, Object> order, RoutePlanner.Stop stop, Instant now) {
        Instant end = instant(order.get("windowEnd"));
        if (Boolean.TRUE.equals(order.get("windowConfirmed")) && end != null && !end.isAfter(now)) {
            return "预约窗口已过，请联系学生并重新确认安排";
        }
        if ("ON_SITE".equals(order.get("taskState")) && order.get("taskUpdatedAt") != null) {
            Instant expected =
                    instant(order.get("taskUpdatedAt"))
                            .plusSeconds(((Number) order.get("estimatedMinutes")).longValue() * 60);
            if (!expected.isAfter(now)) {
                return "现场处理已超过估计时长，请核对剩余工作或记录受阻原因";
            }
        }
        if (stop != null
                && !"ON_SITE".equals(order.get("taskState"))
                && !stop.etaStart().isAfter(now)) {
            return "预计到达时间已过，后续路线将按当前时间重新核算";
        }
        return "";
    }
}
