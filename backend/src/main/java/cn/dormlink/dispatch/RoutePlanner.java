package cn.dormlink.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure planning module: no database, authenticated context, network or mutable application state.
 */
public final class RoutePlanner {
    private static final int DEFAULT_SEARCH_UNITS = 5_000_000;
    private final int searchUnits;

    public RoutePlanner() {
        this(DEFAULT_SEARCH_UNITS);
    }

    /** A unit is one stop in a speculative evaluation, shared by the entire solve. */
    public RoutePlanner(int searchUnits) {
        if (searchUnits < 1) {
            throw new IllegalArgumentException("Search budget must be positive");
        }
        this.searchUnits = searchUnits;
    }

    private static final class Budget {
        private int remaining;

        Budget(int remaining) {
            this.remaining = remaining;
        }

        boolean exhausted() {
            return remaining <= 0;
        }

        boolean spend(int routeSize) {
            int cost = Math.max(1, routeSize);
            if (remaining < cost) {
                remaining = 0;
                return false;
            }
            remaining -= cost;
            return true;
        }
    }

    public record Node(String buildingId, int floorNo) {}

    public record Worker(
            String id,
            Set<String> buildingIds,
            boolean acceptingNew,
            Instant availableUntil,
            Node start,
            List<String> lockedPrefix) {
        public Worker {
            buildingIds = Set.copyOf(buildingIds);
            lockedPrefix = List.copyOf(lockedPrefix);
        }
    }

    public record Job(
            String orderId,
            Node location,
            String priority,
            Instant submittedAt,
            int serviceMinutes,
            Instant windowStart,
            Instant windowEnd,
            boolean hardWindow,
            String ownerId,
            boolean locked,
            String previousManagerId,
            int previousPosition) {}

    public record Snapshot(
            Instant now,
            List<Worker> workers,
            List<Job> jobs,
            Map<String, Integer> buildingTravelMinutes,
            int floorTravelMinutes,
            int sameFloorMinutes) {
        public Snapshot {
            Objects.requireNonNull(now);
            workers = List.copyOf(workers);
            jobs = List.copyOf(jobs);
            buildingTravelMinutes = Map.copyOf(buildingTravelMinutes);
            if (floorTravelMinutes < 0
                    || sameFloorMinutes < 0
                    || jobs.size() > 500
                    || buildingTravelMinutes.values().stream().anyMatch(n -> n < 0)) {
                throw new IllegalArgumentException("Invalid travel cost or more than 500 jobs");
            }
        }
    }

    public record Stop(
            String orderId,
            Instant etaStart,
            Instant etaEnd,
            int travelMinutes,
            int serviceMinutes,
            boolean locked) {}

    public record Route(
            String managerId,
            List<Stop> stops,
            int travelMinutes,
            int waitingMinutes,
            int workloadMinutes) {
        public Route {
            stops = List.copyOf(stops);
        }
    }

    public record Unassigned(String orderId, String code, String reason) {}

    public record Metrics(int travelMinutes, int baselineTravelMinutes, int maxWorkloadMinutes) {}

    public record Result(List<Route> routes, List<Unassigned> unassigned, Metrics metrics) {
        public Result {
            routes = List.copyOf(routes);
            unassigned = List.copyOf(unassigned);
        }
    }

    private static final Comparator<Job> ARRIVAL_ORDER =
            Comparator.comparingInt((Job j) -> "URGENT".equals(j.priority()) ? 0 : 1)
                    .thenComparing(Job::submittedAt)
                    .thenComparing(Job::orderId);
    private static final Comparator<Job> PREVIOUS_ORDER =
            Comparator.comparingInt(Job::previousPosition).thenComparing(Job::orderId);
    private static final Map<String, String> REASONS =
            Map.ofEntries(
                    Map.entry("NO_AVAILABLE_MANAGER", "暂无接受新任务的活跃宿管"),
                    Map.entry("NO_AUTHORIZED_MANAGER", "当前候选宿管无该楼栋权限"),
                    Map.entry("OWNER_UNAVAILABLE", "原办理宿管暂不可安排，请保留责任并确认交接"),
                    Map.entry("POSITION_REQUIRED", "缺少出发位置，请先设置当前楼栋和楼层"),
                    Map.entry("UNREACHABLE", "缺少楼栋之间的通行时间配置"),
                    Map.entry("TIME_WINDOW_CONFLICT", "无法在已确认的上门时间内完成"),
                    Map.entry("WORKING_TIME_EXCEEDED", "超过本次可用工作时段"),
                    Map.entry("LOCK_CONFLICT", "锁定任务与办理责任或楼栋权限不一致"),
                    Map.entry("NO_FEASIBLE_INSERTION", "现有预约或可用时段内没有可行插入位置"),
                    Map.entry("PRIORITY_DEFERRED", "紧急任务优先，本次未认领任务已移回待安排"),
                    Map.entry("BUDGET_EXHAUSTED", "规划预算已用尽，请稍后重试；已保留当前可验证路线"));

    private static final class WorkingRoute {
        final Worker worker;
        List<Job> jobs = new ArrayList<>();
        int lockedCount;

        WorkingRoute(Worker worker) {
            this.worker = worker;
        }
    }

    private record Evaluation(
            List<Stop> stops, int travel, int waiting, int workload, double score, String failure) {
        boolean valid() {
            return failure == null;
        }
    }

    private record Insertion(WorkingRoute route, List<Job> jobs, double cost, List<Job> deferred) {}

    public Result plan(Snapshot input) {
        Budget budget = new Budget(searchUnits);
        Map<String, Job> jobs = new LinkedHashMap<>();
        for (Job j : input.jobs()) {
            if (j.serviceMinutes() < 1
                    || j.serviceMinutes() > 480
                    || j.location() == null
                    || j.submittedAt() == null
                    || jobs.put(j.orderId(), j) != null) {
                throw new IllegalArgumentException("Invalid or duplicate job");
            }
        }
        List<WorkingRoute> routes =
                input.workers().stream()
                        .sorted(Comparator.comparing(Worker::id))
                        .map(WorkingRoute::new)
                        .toList();
        if (routes.stream().map(r -> r.worker.id()).distinct().count() != routes.size()) {
            throw new IllegalArgumentException("Workers must be deduplicated by account");
        }
        Set<String> placed = new HashSet<>();
        Map<String, Unassigned> rejected = new LinkedHashMap<>();
        for (WorkingRoute r : routes) {
            List<String> prefix = new ArrayList<>(r.worker.lockedPrefix());
            jobs.values().stream()
                    .filter(j -> j.locked() && r.worker.id().equals(j.ownerId()))
                    .sorted(PREVIOUS_ORDER)
                    .map(Job::orderId)
                    .filter(id -> !prefix.contains(id))
                    .forEach(prefix::add);
            for (String id : prefix) {
                Job j = jobs.get(id);
                if (j == null || placed.contains(id)) {
                    continue;
                }
                if (!eligible(r.worker, j) || !j.locked()) {
                    rejected.put(id, rejected(j, "LOCK_CONFLICT"));
                    placed.add(id);
                    continue;
                }
                var candidate = new ArrayList<>(r.jobs);
                candidate.add(j);
                Evaluation e = evaluate(input, r.worker, candidate);
                if (!e.valid()) {
                    rejected.put(id, rejected(j, e.failure()));
                } else {
                    r.jobs = candidate;
                    r.lockedCount++;
                }
                placed.add(id);
            }
            // Keep a feasible existing suffix. A new arrival is inserted around it, not rebuilt
            // from
            // zero.
            jobs.values().stream()
                    .filter(j -> !placed.contains(j.orderId()) && !j.locked())
                    .filter(
                            j ->
                                    r.worker.id().equals(j.previousManagerId())
                                            && eligible(r.worker, j))
                    .sorted(PREVIOUS_ORDER)
                    .forEach(
                            j -> {
                                var candidate = new ArrayList<>(r.jobs);
                                candidate.add(j);
                                if (tryEvaluate(input, r.worker, candidate, budget).valid()) {
                                    r.jobs = candidate;
                                    placed.add(j.orderId());
                                }
                            });
        }
        for (Job j :
                jobs.values().stream()
                        .filter(j -> !placed.contains(j.orderId()))
                        .sorted(ARRIVAL_ORDER)
                        .toList()) {
            if (j.locked()) {
                rejected.put(j.orderId(), rejected(j, "LOCK_CONFLICT"));
                continue;
            }
            List<WorkingRoute> candidates =
                    routes.stream().filter(r -> eligible(r.worker, j)).toList();
            if (candidates.isEmpty()) {
                String code =
                        j.ownerId() != null
                                ? "OWNER_UNAVAILABLE"
                                : routes.stream().noneMatch(r -> r.worker.acceptingNew())
                                        ? "NO_AVAILABLE_MANAGER"
                                        : "NO_AUTHORIZED_MANAGER";
                rejected.put(j.orderId(), rejected(j, code));
                continue;
            }
            if (budget.exhausted()) {
                rejected.put(j.orderId(), rejected(j, "BUDGET_EXHAUSTED"));
                continue;
            }
            Insertion best = null;
            Set<String> failures = new LinkedHashSet<>();
            int oldMax =
                    routes.stream()
                            .mapToInt(r -> evaluate(input, r.worker, r.jobs).workload())
                            .max()
                            .orElse(0);
            for (WorkingRoute r : candidates) {
                var insertion =
                        findInsertion(input, r, r.jobs, j, oldMax, List.of(), failures, budget);
                if (insertion != null && (best == null || insertion.cost() < best.cost() - .0001)) {
                    best = insertion;
                }
            }
            if (best == null && !budget.exhausted() && "URGENT".equals(j.priority())) {
                for (WorkingRoute r : candidates) {
                    var remaining = new ArrayList<>(r.jobs);
                    List<Job> deferred = new ArrayList<>();
                    for (int k = remaining.size() - 1;
                            k >= r.lockedCount && !budget.exhausted();
                            k--) {
                        Job existing = remaining.get(k);
                        if (existing.ownerId() != null
                                || existing.locked()
                                || existing.hardWindow()
                                || "URGENT".equals(existing.priority())) {
                            continue;
                        }
                        deferred.add(remaining.remove(k));
                        var insertion =
                                findInsertion(
                                        input, r, remaining, j, oldMax, deferred, failures, budget);
                        if (insertion != null
                                && (best == null || insertion.cost() < best.cost() - .0001)) {
                            best = insertion;
                        }
                    }
                }
            }
            if (best == null) {
                rejected.put(
                        j.orderId(),
                        rejected(
                                j,
                                budget.exhausted()
                                        ? "BUDGET_EXHAUSTED"
                                        : failures.size() == 1
                                                ? failures.iterator().next()
                                                : "NO_FEASIBLE_INSERTION"));
            } else {
                best.route().jobs = best.jobs();
                for (Job deferred : best.deferred()) {
                    rejected.put(deferred.orderId(), rejected(deferred, "PRIORITY_DEFERRED"));
                }
            }
        }
        improveAcrossRoutes(input, routes, budget);
        List<Route> result = new ArrayList<>();
        int travel = 0, baseline = 0, maximum = 0;
        boolean comparable = true;
        for (WorkingRoute r : routes) {
            improveSuffix(input, r, budget);
            Evaluation e = evaluate(input, r.worker, r.jobs);
            result.add(new Route(r.worker.id(), e.stops(), e.travel(), e.waiting(), e.workload()));
            travel += e.travel();
            maximum = Math.max(maximum, e.workload());
            List<Job> original = new ArrayList<>(r.jobs.subList(0, r.lockedCount));
            r.jobs.subList(r.lockedCount, r.jobs.size()).stream()
                    .sorted(Comparator.comparing(Job::submittedAt).thenComparing(Job::orderId))
                    .forEach(original::add);
            Evaluation base = evaluate(input, r.worker, original);
            if (!base.valid()) {
                comparable = false;
            } else {
                baseline += base.travel();
            }
        }
        return new Result(
                result,
                new ArrayList<>(rejected.values()),
                new Metrics(travel, comparable ? baseline : -1, maximum));
    }

    private boolean eligible(Worker w, Job j) {
        return w.buildingIds().contains(j.location().buildingId())
                && (j.ownerId() == null ? w.acceptingNew() : j.ownerId().equals(w.id()));
    }

    private Insertion findInsertion(
            Snapshot input,
            WorkingRoute r,
            List<Job> remaining,
            Job job,
            int oldMax,
            List<Job> deferred,
            Set<String> failures,
            Budget budget) {
        Evaluation before = tryEvaluate(input, r.worker, r.jobs, budget);
        Insertion best = null;
        for (int position = r.lockedCount;
                position <= remaining.size() && !budget.exhausted();
                position++) {
            var candidate = new ArrayList<>(remaining);
            candidate.add(position, job);
            Evaluation after = tryEvaluate(input, r.worker, candidate, budget);
            if (!after.valid()) {
                failures.add(after.failure());
                continue;
            }
            double cost =
                    after.score()
                            - before.score()
                            + .5 * Math.max(0, after.workload() - oldMax)
                            + 500 * deferred.size();
            if (best == null || cost < best.cost() - .0001) {
                best = new Insertion(r, candidate, cost, List.copyOf(deferred));
            }
        }
        return best;
    }

    private Evaluation tryEvaluate(Snapshot input, Worker worker, List<Job> jobs, Budget budget) {
        return budget.spend(jobs.size())
                ? evaluate(input, worker, jobs)
                : failed("BUDGET_EXHAUSTED");
    }

    private Evaluation evaluate(Snapshot in, Worker worker, List<Job> jobs) {
        Instant time = in.now();
        Node current = worker.start();
        Instant priorityAnchorTime = time;
        Node priorityAnchorLocation = current;
        boolean ordinaryBefore = false;
        int travel = 0, waiting = 0;
        double penalty = 0;
        List<Stop> stops = new ArrayList<>();
        int[] positions = null;
        int[] seenPositions = null;
        int previousCount = 0;
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (current == null || current.buildingId() == null) {
                return failed("POSITION_REQUIRED");
            }
            int minutes =
                    i == 0 && j.locked() && current.equals(j.location())
                            ? 0
                            : distance(in, current, j.location());
            if (minutes < 0) {
                return failed("UNREACHABLE");
            }
            time = time.plusSeconds(minutes * 60L);
            travel += minutes;
            if (!j.locked() && j.windowStart() != null && time.isBefore(j.windowStart())) {
                Instant proposedEnd = j.windowStart().plusSeconds(j.serviceMinutes() * 60L);
                if (j.hardWindow()
                        || worker.availableUntil() == null
                        || !proposedEnd.isAfter(worker.availableUntil())) {
                    waiting += ceilMinutes(Duration.between(time, j.windowStart()));
                    time = j.windowStart();
                }
            }
            Instant finish = time.plusSeconds(j.serviceMinutes() * 60L);
            if (!j.locked()
                    && j.hardWindow()
                    && j.windowEnd() != null
                    && finish.isAfter(j.windowEnd())) {
                return failed("TIME_WINDOW_CONFLICT");
            }
            if (!j.locked()
                    && worker.availableUntil() != null
                    && finish.isAfter(worker.availableUntil())) {
                return failed("WORKING_TIME_EXCEEDED");
            }
            if (!j.locked() && !j.hardWindow()) {
                if (j.windowStart() != null && time.isBefore(j.windowStart())) {
                    penalty += 3 * ceilMinutes(Duration.between(time, j.windowStart()));
                }
                if (j.windowEnd() != null && finish.isAfter(j.windowEnd())) {
                    penalty += 3 * ceilMinutes(Duration.between(j.windowEnd(), finish));
                }
            }
            if (j.previousManagerId() != null && !j.previousManagerId().equals(worker.id())) {
                penalty += 10;
            }
            if (worker.id().equals(j.previousManagerId()) && j.previousPosition() >= 0) {
                if (positions == null) {
                    positions =
                            jobs.stream()
                                    .filter(
                                            old ->
                                                    old.previousPosition() >= 0
                                                            && worker.id()
                                                                    .equals(
                                                                            old
                                                                                    .previousManagerId()))
                                    .mapToInt(Job::previousPosition)
                                    .distinct()
                                    .sorted()
                                    .toArray();
                    seenPositions = new int[positions.length + 1];
                }
                int rank = Arrays.binarySearch(positions, j.previousPosition()) + 1;
                int noGreater = 0;
                for (int k = rank; k > 0; k -= k & -k) {
                    noGreater += seenPositions[k];
                }
                penalty += 3.0 * (previousCount - noGreater);
                for (int k = rank; k < seenPositions.length; k += k & -k) {
                    seenPositions[k]++;
                }
                previousCount++;
            }
            // Ordinary stops may fill an appointment's waiting gap, but must not delay urgent
            // access.
            if (!j.locked() && "URGENT".equals(j.priority()) && ordinaryBefore) {
                int directMinutes = distance(in, priorityAnchorLocation, j.location());
                if (directMinutes >= 0) {
                    Instant earliest = priorityAnchorTime.plusSeconds(directMinutes * 60L);
                    if (j.windowStart() != null && earliest.isBefore(j.windowStart())) {
                        earliest = j.windowStart();
                    }
                    penalty += 10000.0 * ceilMinutes(Duration.between(earliest, time));
                }
            }
            stops.add(new Stop(j.orderId(), time, finish, minutes, j.serviceMinutes(), j.locked()));
            current = j.location();
            time = finish;
            if (j.locked()) {
                priorityAnchorTime = finish;
                priorityAnchorLocation = current;
                ordinaryBefore = false;
            } else if (!"URGENT".equals(j.priority())) {
                ordinaryBefore = true;
            }
        }
        int workload = ceilMinutes(Duration.between(in.now(), time));
        return new Evaluation(stops, travel, waiting, workload, travel + waiting + penalty, null);
    }

    private int distance(Snapshot in, Node from, Node to) {
        if (from.buildingId().equals(to.buildingId())) {
            return Math.abs(from.floorNo() - to.floorNo()) * in.floorTravelMinutes()
                    + in.sameFloorMinutes();
        }
        Integer link = in.buildingTravelMinutes().get(from.buildingId() + ":" + to.buildingId());
        if (link == null) {
            return -1;
        }
        return link
                + (Math.max(0, from.floorNo() - 1) + Math.max(0, to.floorNo() - 1))
                        * in.floorTravelMinutes()
                + 2 * in.sameFloorMinutes();
    }

    private record Relocation(
            WorkingRoute from, List<Job> remaining, WorkingRoute to, List<Job> expanded) {}

    private void improveAcrossRoutes(Snapshot in, List<WorkingRoute> routes, Budget budget) {
        // ponytail: bounded relocation keeps new online staff useful without a separate fleet
        // solver.
        int relocationCandidates = 2000;
        for (int pass = 0; pass < 3 && !budget.exhausted() && relocationCandidates > 0; pass++) {
            Map<WorkingRoute, Evaluation> before = new HashMap<>();
            double sum = 0;
            int maximum = 0;
            for (WorkingRoute route : routes) {
                Evaluation e = evaluate(in, route.worker, route.jobs);
                before.put(route, e);
                sum += e.score();
                maximum = Math.max(maximum, e.workload());
            }
            double best = sum + maximum;
            Relocation selected = null;
            search:
            for (WorkingRoute from : routes) {
                for (int i = from.lockedCount; i < from.jobs.size(); i++) {
                    Job job = from.jobs.get(i);
                    if (job.ownerId() != null || job.locked() || job.hardWindow()) {
                        continue;
                    }
                    List<Job> remaining = new ArrayList<>(from.jobs);
                    remaining.remove(i);
                    Evaluation reduced = tryEvaluate(in, from.worker, remaining, budget);
                    if (!reduced.valid()) {
                        continue;
                    }
                    for (WorkingRoute to : routes) {
                        if (from == to || !eligible(to.worker, job)) {
                            continue;
                        }
                        int otherMaximum = 0;
                        for (WorkingRoute other : routes) {
                            if (other != from && other != to) {
                                otherMaximum = Math.max(otherMaximum, before.get(other).workload());
                            }
                        }
                        for (int position = to.lockedCount;
                                position <= to.jobs.size();
                                position++) {
                            if (budget.exhausted() || relocationCandidates-- <= 0) {
                                break search;
                            }
                            List<Job> expanded = new ArrayList<>(to.jobs);
                            expanded.add(position, job);
                            Evaluation increased = tryEvaluate(in, to.worker, expanded, budget);
                            if (!increased.valid()) {
                                continue;
                            }
                            double score =
                                    sum
                                            - before.get(from).score()
                                            - before.get(to).score()
                                            + reduced.score()
                                            + increased.score()
                                            + Math.max(
                                                    otherMaximum,
                                                    Math.max(
                                                            reduced.workload(),
                                                            increased.workload()));
                            if (score < best - .0001) {
                                best = score;
                                selected = new Relocation(from, remaining, to, expanded);
                            }
                        }
                    }
                }
            }
            if (selected == null) {
                break;
            }
            selected.from().jobs = selected.remaining();
            selected.to().jobs = selected.expanded();
        }
    }

    private void improveSuffix(Snapshot in, WorkingRoute r, Budget budget) {
        // ponytail: bounded 2-opt for course-sized batches; replace the solver if larger batches
        // need
        // it.
        for (int pass = 0; pass < 2 && !budget.exhausted(); pass++) {
            double best = evaluate(in, r.worker, r.jobs).score();
            List<Job> selected = null;
            for (int start = r.lockedCount;
                    start < r.jobs.size() - 1 && !budget.exhausted();
                    start++) {
                for (int end = start + 1; end < r.jobs.size() && !budget.exhausted(); end++) {
                    var proposal = new ArrayList<>(r.jobs);
                    Collections.reverse(proposal.subList(start, end + 1));
                    Evaluation e = tryEvaluate(in, r.worker, proposal, budget);
                    if (e.valid() && e.score() < best - .0001) {
                        best = e.score();
                        selected = proposal;
                    }
                }
            }
            if (selected == null) {
                break;
            }
            r.jobs = selected;
        }
    }

    private Evaluation failed(String code) {
        return new Evaluation(List.of(), 0, 0, 0, Double.POSITIVE_INFINITY, code);
    }

    private Unassigned rejected(Job j, String code) {
        return new Unassigned(j.orderId(), code, REASONS.getOrDefault(code, "暂时无法安排"));
    }

    private static int ceilMinutes(Duration d) {
        return (int) Math.max(0, (d.getSeconds() + 59) / 60);
    }
}
