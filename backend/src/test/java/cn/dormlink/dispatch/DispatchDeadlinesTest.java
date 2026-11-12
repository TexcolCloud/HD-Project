package cn.dormlink.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class DispatchDeadlinesTest {
    private final Instant now = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void excludedRowsDoNotDisplaceTheActualPlanningBatchsAppointmentBoundaries() {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) {
            rows.add(
                    Map.of(
                            "id",
                            "blocked-" + i,
                            "taskState",
                            "BLOCKED",
                            "windowEnd",
                            now.plusSeconds(120).toString()));
        }
        rows.add(Map.of("id", "active", "windowEnd", now.plusSeconds(180).toString()));
        var plan =
                new RoutePlanner.Result(
                        List.of(),
                        List.of(new RoutePlanner.Unassigned("active", "NO_WORKER", "暂无可用人员")),
                        new RoutePlanner.Metrics(0, 0, 0));
        assertThat(DispatchDeadlines.next(plan, rows, now)).isEqualTo(now.plusSeconds(180));
    }

    @Test
    void elapsedExecutionTriggersBoundedRechecksWithoutADeviceEvent() {
        var order =
                Map.<String, Object>of(
                        "id",
                        "1",
                        "taskState",
                        "ON_SITE",
                        "taskUpdatedAt",
                        now.minusSeconds(3600).toString(),
                        "estimatedMinutes",
                        30);
        var stop = new RoutePlanner.Stop("1", now, now.plusSeconds(1800), 0, 30, true);
        var plan =
                new RoutePlanner.Result(
                        List.of(new RoutePlanner.Route("m", List.of(stop), 0, 0, 30)),
                        List.of(),
                        new RoutePlanner.Metrics(0, 0, 30));
        assertThat(DispatchDeadlines.next(plan, List.of(order), now))
                .isEqualTo(now.plusSeconds(60));
        assertThat(DispatchDeadlines.warning(order, stop, now)).contains("超过估计时长");
        assertThat(DispatchDeadlines.next(plan, List.of(order), now.plusSeconds(60)))
                .isEqualTo(now.plusSeconds(120));
    }

    @Test
    void unassignedAppointmentsStillHaveTimeBoundariesAndEmptyPlansDoNotPoll() {
        var order =
                Map.<String, Object>of(
                        "id",
                        "2",
                        "windowEnd",
                        now.plusSeconds(180).toString(),
                        "windowConfirmed",
                        true);
        assertThat(DispatchDeadlines.next(null, List.of(order), now))
                .isEqualTo(now.plusSeconds(180));
        assertThat(DispatchDeadlines.warning(order, null, now.plusSeconds(180))).contains("预约窗口已过");
        assertThat(DispatchDeadlines.next(null, List.of(), now)).isNull();
    }

    @Test
    void unchangedOverdueWorkIsConsumedOnceWhileItsAppointmentEndStillTriggers() {
        var order =
                Map.<String, Object>of(
                        "id",
                        "1",
                        "version",
                        1,
                        "taskVersion",
                        1,
                        "taskState",
                        "ON_SITE",
                        "taskUpdatedAt",
                        now.minusSeconds(3600).toString(),
                        "estimatedMinutes",
                        30,
                        "windowEnd",
                        now.plusSeconds(600).toString());
        var stop = new RoutePlanner.Stop("1", now, now.plusSeconds(1800), 0, 30, true);
        var plan =
                new RoutePlanner.Result(
                        List.of(new RoutePlanner.Route("m", List.of(stop), 0, 0, 30)),
                        List.of(),
                        new RoutePlanner.Metrics(0, 0, 30));
        var deadlines = new DispatchDeadlines();
        assertThat(deadlines.planned(plan, List.of(order), now)).isEqualTo(now.plusSeconds(60));
        assertThat(deadlines.acknowledged(now.plusSeconds(60))).isEqualTo(now.plusSeconds(600));
        assertThat(deadlines.planned(plan, List.of(order), now.plusSeconds(60)))
                .isEqualTo(now.plusSeconds(600));
        assertThat(deadlines.acknowledged(now.plusSeconds(600))).isNull();
        for (int minute = 11; minute < 100; minute++) {
            assertThat(deadlines.planned(plan, List.of(order), now.plusSeconds(minute * 60L)))
                    .isNull();
        }
    }
}
