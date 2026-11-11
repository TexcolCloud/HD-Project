import cn.dormlink.dispatch.RoutePlanner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Same immutable synthetic input can be compiled against the before and after planner. */
public final class PlannerBenchmark {
    public static void main(String[] args) {
        int workerCount = Integer.parseInt(args[0]);
        int jobCount = Integer.parseInt(args[1]);
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        var workers = new ArrayList<RoutePlanner.Worker>();
        var jobs = new ArrayList<RoutePlanner.Job>();
        var buildings = List.of("A", "B", "C", "D");
        var costs = new java.util.HashMap<String, Integer>();
        for (String from : buildings) {
            for (String to : buildings) {
                if (!from.equals(to)) {
                    costs.put(from + ":" + to, 4);
                }
            }
        }
        for (int i = 0; i < workerCount; i++) {
            workers.add(new RoutePlanner.Worker("w" + i, Set.copyOf(buildings), true,
                    now.plusSeconds(86_400), new RoutePlanner.Node(buildings.get(i % 4), 1), List.of()));
        }
        for (int i = 0; i < jobCount; i++) {
            boolean confirmed = i % 11 == 0;
            jobs.add(new RoutePlanner.Job("j" + i,
                    new RoutePlanner.Node(buildings.get(i % 4), i % 6 + 1),
                    i % 7 == 0 ? "URGENT" : "NORMAL", now.minusSeconds(jobCount - i),
                    15, confirmed ? now : null, confirmed ? now.plusSeconds(43_200) : null,
                    confirmed, null, false, null, -1));
        }
        var snapshot = new RoutePlanner.Snapshot(now, workers, jobs, costs, 2, 1);
        var planner = new RoutePlanner();
        planner.plan(snapshot);
        long[] times = new long[7];
        RoutePlanner.Result result = null;
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            result = planner.plan(snapshot);
            times[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(times);
        int assigned = result.routes().stream().mapToInt(route -> route.stops().size()).sum();
        long exhausted = result.unassigned().stream().filter(item -> "BUDGET_EXHAUSTED".equals(item.code())).count();
        System.out.printf(java.util.Locale.ROOT,
                "{\"workers\":%d,\"jobs\":%d,\"samples\":7,\"p50Ms\":%.3f,\"maxMs\":%.3f,\"assigned\":%d,\"unassigned\":%d,\"budgetDeferred\":%d,\"travelMinutes\":%d}%n",
                workerCount, jobCount, times[3] / 1_000_000.0, times[6] / 1_000_000.0,
                assigned, result.unassigned().size(), exhausted, result.metrics().travelMinutes());
    }
}
