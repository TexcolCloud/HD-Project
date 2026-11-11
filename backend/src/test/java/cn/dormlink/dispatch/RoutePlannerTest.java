package cn.dormlink.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutePlannerTest {
  final Instant now = Instant.parse("2026-11-07T00:00:00Z");

  @Test
  void exhaustedSearchReturnsOnlyCurrentlyFeasibleWorkAndExplainsEveryRemainingJob() {
    var worker = new RoutePlanner.Worker("1", Set.of("A"), true,
        now.plusSeconds(7200), new RoutePlanner.Node("A", 1), List.of());
    var result = new RoutePlanner(1).plan(new RoutePlanner.Snapshot(now, List.of(worker),
        List.of(job("old-unauthorized", "B", 1, null, false, "1", 0),
            job("first", "A", 1, null, false, null, -1),
            job("second", "A", 1, null, false, null, -1)), Map.of(), 2, 1));
    assertThat(result.routes().getFirst().stops()).isEmpty();
    assertThat(result.unassigned()).extracting(RoutePlanner.Unassigned::orderId)
        .containsExactlyInAnyOrder("old-unauthorized", "first", "second");
    assertThat(result.unassigned()).filteredOn(u -> !u.orderId().equals("old-unauthorized"))
        .extracting(RoutePlanner.Unassigned::code).containsOnly("BUDGET_EXHAUSTED");
  }

  @Test
  void ordinaryWorkCanFillAnUrgentAppointmentGapWithoutDelayingItsVisit() {
    var worker =
        new RoutePlanner.Worker(
            "1",
            Set.of("A"),
            true,
            now.plusSeconds(8 * 3600),
            new RoutePlanner.Node("A", 1),
            List.of());
    Instant appointment = now.plusSeconds(5 * 3600);
    var urgent =
        new RoutePlanner.Job(
            "urgent",
            new RoutePlanner.Node("A", 1),
            "URGENT",
            now,
            10,
            appointment,
            appointment.plusSeconds(3600),
            true,
            null,
            false,
            null,
            -1);
    var normal = job("normal", "A", 1, null, false, null, -1);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now, List.of(worker), List.of(urgent, normal), Map.of(), 2, 1));
    assertThat(result.unassigned()).isEmpty();
    var stops = result.routes().getFirst().stops();
    assertThat(stops).extracting(RoutePlanner.Stop::orderId).containsExactly("normal", "urgent");
    assertThat(stops.getFirst().etaStart()).isBefore(appointment);
    assertThat(stops.get(1).etaStart()).isEqualTo(appointment);
  }

  @Test
  void aNewlyOnlineManagerCanShareExistingUnclaimedWorkWithoutMovingOwnedTasks() {
    var first =
        new RoutePlanner.Worker(
            "1",
            Set.of("A"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 1),
            List.of());
    var newcomer =
        new RoutePlanner.Worker(
            "2",
            Set.of("A"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 1),
            List.of());
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now,
                    List.of(first, newcomer),
                    List.of(
                        job("a", "A", 1, null, false, "1", 0),
                        job("b", "A", 1, null, false, "1", 1),
                        job("c", "A", 1, null, false, "1", 2)),
                    Map.of(),
                    2,
                    1));
    assertThat(result.routes().stream().map(r -> r.stops().size()).toList()).containsExactly(2, 1);
  }

  @Test
  void insertsNewJobAfterLockedCurrentAndNeverBreaksBuildingScope() {
    var planner = new RoutePlanner();
    var a =
        new RoutePlanner.Worker(
            "1",
            Set.of("A", "B"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 3),
            List.of("current"));
    var b =
        new RoutePlanner.Worker(
            "2",
            Set.of("B"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("B", 1),
            List.of());
    var current = job("current", "A", 3, "1", true, "1", 0);
    var previous = job("old", "A", 5, null, false, "1", 1);
    var added = job("new", "A", 4, null, false, null, -1);
    var result =
        planner.plan(
            new RoutePlanner.Snapshot(
                now,
                List.of(a, b),
                List.of(current, previous, added),
                Map.of("A:B", 8, "B:A", 8),
                2,
                1));
    assertThat(result.unassigned()).isEmpty();
    assertThat(
            result.routes().stream()
                .filter(r -> r.managerId().equals("1"))
                .findFirst()
                .orElseThrow()
                .stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("current", "new", "old");
    assertThat(
            result.routes().stream()
                .filter(r -> r.managerId().equals("2"))
                .findFirst()
                .orElseThrow()
                .stops())
        .isEmpty();
  }

  RoutePlanner.Job job(
      String id,
      String building,
      int floor,
      String owner,
      boolean locked,
      String previous,
      int position) {
    return new RoutePlanner.Job(
        id,
        new RoutePlanner.Node(building, floor),
        "NORMAL",
        now.minusSeconds(120),
        10,
        null,
        null,
        false,
        owner,
        locked,
        previous,
        position);
  }

  @Test
  void urgentArrivalCanDisplaceAnUnacceptedRecommendationWhenShiftIsFull() {
    var worker =
        new RoutePlanner.Worker(
            "1", Set.of("A"), true, now.plusSeconds(900), new RoutePlanner.Node("A", 1), List.of());
    var old = job("normal", "A", 1, null, false, "1", 0);
    var urgent =
        new RoutePlanner.Job(
            "urgent",
            new RoutePlanner.Node("A", 1),
            "URGENT",
            now,
            10,
            null,
            null,
            false,
            null,
            false,
            null,
            -1);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now, List.of(worker), List.of(old, urgent), Map.of(), 2, 1));
    assertThat(result.routes().getFirst().stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("urgent");
    assertThat(result.unassigned())
        .extracting(RoutePlanner.Unassigned::orderId)
        .containsExactly("normal");
  }

  @Test
  void satisfiesConfirmedWindowAndDoesNotClaimSavingsAgainstAnInfeasibleBaseline() {
    var worker =
        new RoutePlanner.Worker(
            "1",
            Set.of("A"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 1),
            List.of());
    var older = job("older", "A", 1, null, false, null, -1);
    var appointment =
        new RoutePlanner.Job(
            "appointment",
            new RoutePlanner.Node("A", 1),
            "NORMAL",
            now,
            10,
            now,
            now.plusSeconds(720),
            true,
            null,
            false,
            null,
            -1);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now, List.of(worker), List.of(older, appointment), Map.of(), 2, 1));
    assertThat(result.unassigned()).isEmpty();
    assertThat(result.routes().getFirst().stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("appointment", "older");
    assertThat(result.metrics().baselineTravelMinutes()).isEqualTo(-1);
  }

  @Test
  void offlineOwnerKeepsCurrentTaskPastShiftEndButReceivesNoNewWork() {
    var worker =
        new RoutePlanner.Worker(
            "1",
            Set.of("A"),
            false,
            now.minusSeconds(60),
            new RoutePlanner.Node("A", 3),
            List.of("current"));
    var current = job("current", "A", 3, "1", true, "1", 0);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now,
                    List.of(worker),
                    List.of(current, job("new", "A", 4, null, false, null, -1)),
                    Map.of(),
                    2,
                    1));
    assertThat(result.routes().getFirst().stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("current");
    assertThat(result.unassigned())
        .extracting(RoutePlanner.Unassigned::code)
        .containsExactly("NO_AVAILABLE_MANAGER");
  }

  @Test
  void usesRemainingDurationToShareWorkAcrossTwoManagers() {
    var first =
        new RoutePlanner.Worker(
            "1",
            Set.of("A"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 1),
            List.of());
    var second =
        new RoutePlanner.Worker(
            "2",
            Set.of("A"),
            true,
            now.plusSeconds(7200),
            new RoutePlanner.Node("A", 1),
            List.of());
    var longJob =
        new RoutePlanner.Job(
            "long",
            new RoutePlanner.Node("A", 1),
            "NORMAL",
            now.minusSeconds(600),
            60,
            null,
            null,
            false,
            "1",
            false,
            "1",
            0);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now,
                    List.of(first, second),
                    List.of(longJob, job("new", "A", 1, null, false, null, -1)),
                    Map.of(),
                    2,
                    1));
    assertThat(result.routes().get(0).stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("long");
    assertThat(result.routes().get(1).stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("new");
  }

  @Test
  void urgentArrivalCannotEvictAnAlreadyConfirmedAppointment() {
    var worker =
        new RoutePlanner.Worker(
            "1", Set.of("A"), true, now.plusSeconds(900), new RoutePlanner.Node("A", 1), List.of());
    var confirmed =
        new RoutePlanner.Job(
            "confirmed",
            new RoutePlanner.Node("A", 1),
            "NORMAL",
            now.minusSeconds(120),
            10,
            now,
            now.plusSeconds(900),
            true,
            null,
            false,
            "1",
            0);
    var urgent =
        new RoutePlanner.Job(
            "urgent",
            new RoutePlanner.Node("A", 1),
            "URGENT",
            now,
            10,
            null,
            null,
            false,
            null,
            false,
            null,
            -1);
    var result =
        new RoutePlanner()
            .plan(
                new RoutePlanner.Snapshot(
                    now, List.of(worker), List.of(confirmed, urgent), Map.of(), 2, 1));
    assertThat(result.routes().getFirst().stops())
        .extracting(RoutePlanner.Stop::orderId)
        .containsExactly("confirmed");
    assertThat(result.unassigned())
        .extracting(RoutePlanner.Unassigned::orderId)
        .containsExactly("urgent");
  }
}
