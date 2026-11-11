package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dormlink.fieldservice.OperationsService;
import cn.dormlink.housing.HousingService;
import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Optional real-MySQL/openGauss regressions; fixtures are unique and never delete existing data.
 */
@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@EnabledIf("databaseConfigured")
@SpringBootTest(properties = {"app.seed=true", "app.ai.api-key="})
class MySqlConcurrencyTest {
    static boolean databaseConfigured() {
        return OpenGaussTestDatabase.configured()
                || (System.getenv("MYSQL_TEST_URL") != null
                        && !System.getenv("MYSQL_TEST_URL").isBlank());
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        if (OpenGaussTestDatabase.configured()) {
            OpenGaussTestDatabase.configure(r, "concurrency");
            return;
        }
        if (!System.getenv("MYSQL_TEST_URL").contains("dormlink_concurrency_test"))
            throw new IllegalArgumentException("Use the isolated dormlink_concurrency_test schema");
        r.add("spring.datasource.url", () -> System.getenv("MYSQL_TEST_URL"));
        r.add("spring.datasource.username", () -> System.getenv("DB_USER"));
        r.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Autowired Db db;
    @Autowired HousingService housing;
    @Autowired BusinessSupport ops;
    @Autowired OperationsService operations;

    Actor actor(String username) {
        var u = db.one("SELECT id,display_name,role FROM sys_user WHERE username=?", username);
        return new Actor(
                u.get("id").toString(), u.get("displayName").toString(), "MANAGER", null, "test");
    }

    <T> T as(Actor actor, Supplier<T> work) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> command(Actor actor, Map<String, Object> body, Supplier<Object> work) {
        return as(
                actor,
                () ->
                        (Map<String, Object>)
                                ops.command("TEST", UUID.randomUUID().toString(), body, work));
    }

    Map<String, Object> student(Actor manager, String suffix) {
        var body =
                Map.<String, Object>of(
                        "studentNo",
                        "C" + suffix,
                        "name",
                        "并发回归学生",
                        "college",
                        "虚构学院",
                        "phone",
                        "13800000000");
        return command(manager, body, () -> housing.saveStudent(null, body));
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Concurrency checkpoint timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roomMutationMustObserveAnEnrollmentCommittedAfterCommandSnapshot(boolean shrink)
            throws Exception {
        Actor editor = actor("manager1"), entrant = actor("manager2");
        String suffix = Long.toString(System.nanoTime());
        String building =
                db.one(
                                "SELECT building_id FROM manager_building WHERE manager_user_id=?"
                                        + " LIMIT 1",
                                entrant.id())
                        .get("buildingId")
                        .toString();
        var create =
                Map.<String, Object>of(
                        "buildingId",
                        building,
                        "roomNo",
                        "C" + suffix,
                        "floorNo",
                        1,
                        "capacity",
                        2);
        var room = command(editor, create, () -> housing.saveRoom(null, create));
        if (shrink) {
            var first = student(editor, suffix + "A");
            var checkIn =
                    Map.<String, Object>of("studentId", first.get("id"), "roomId", room.get("id"));
            command(editor, checkIn, () -> housing.checkIn(checkIn));
        }
        var next = student(editor, suffix + "B");
        var checkIn = Map.<String, Object>of("studentId", next.get("id"), "roomId", room.get("id"));
        var edit = new LinkedHashMap<String, Object>(create);
        edit.put("expectedVersion", 0);
        edit.put("enabled", shrink);
        edit.put("capacity", shrink ? 1 : 2);
        var snapshotEstablished = new CountDownLatch(1);
        var enrollmentCommitted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var editResult =
                    workers.submit(
                            () -> {
                                try {
                                    command(
                                            editor,
                                            edit,
                                            () -> {
                                                snapshotEstablished.countDown();
                                                await(enrollmentCommitted);
                                                return housing.saveRoom(
                                                        room.get("id").toString(), edit);
                                            });
                                    return 200;
                                } catch (ApiException e) {
                                    return e.status();
                                }
                            });
            var enrollment =
                    workers.submit(
                            () -> {
                                await(snapshotEstablished);
                                try {
                                    return command(
                                            entrant, checkIn, () -> housing.checkIn(checkIn));
                                } finally {
                                    enrollmentCommitted.countDown();
                                }
                            });
            assertThat(enrollment.get(15, TimeUnit.SECONDS))
                    .containsEntry("studentId", next.get("id"));
            assertThat(editResult.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        }
        var persisted = db.one("SELECT enabled,capacity FROM dorm_room WHERE id=?", room.get("id"));
        assertThat(persisted.get("enabled")).isEqualTo(true);
        assertThat(Db.number(persisted, "capacity")).isEqualTo(2);
    }

    @Test
    void twoBuildingEditsWithTheSameVersionCannotBothCommit() throws Exception {
        Actor first = actor("manager1"), second = actor("manager2");
        String suffix = Long.toString(System.nanoTime());
        var create =
                Map.<String, Object>of("code", "C" + suffix, "name", "并发回归楼栋", "floorCount", 4);
        var building = command(first, create, () -> housing.saveBuilding(null, create));
        db.update(
                "INSERT INTO manager_building(manager_user_id,building_id) VALUES(?,?)",
                second.id(),
                building.get("id"));
        var edit = new LinkedHashMap<String, Object>(create);
        edit.put("expectedVersion", 0);
        var snapshots = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (Actor manager : List.of(first, second)) {
                tasks.add(
                        workers.submit(
                                () -> {
                                    try {
                                        command(
                                                manager,
                                                edit,
                                                () -> {
                                                    snapshots.countDown();
                                                    await(snapshots);
                                                    return housing.saveBuilding(
                                                            building.get("id").toString(), edit);
                                                });
                                        return 200;
                                    } catch (ApiException e) {
                                        return e.status();
                                    }
                                }));
            }
            assertThat(
                            List.of(
                                    tasks.get(0).get(15, TimeUnit.SECONDS),
                                    tasks.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(db.count("SELECT version FROM building WHERE id=?", building.get("id")))
                .isEqualTo(1);
    }

    @Test
    void visitorDepartureCannotBeOverwrittenByAnotherSameVersionDeparture() throws Exception {
        Actor first = actor("manager1"), second = actor("manager2");
        String suffix = Long.toString(System.nanoTime());
        String studentId =
                db.one(
                                "SELECT s.id FROM student s JOIN sys_user u ON u.id=s.user_id WHERE"
                                        + " u.username='student2'")
                        .get("id")
                        .toString();
        var body =
                Map.<String, Object>of(
                        "visitorName",
                        "并发离访回归",
                        "phone",
                        "13900000000",
                        "visitedStudentId",
                        studentId,
                        "purpose",
                        "虚构回归",
                        "confirmDuplicate",
                        true);
        var visitor = command(first, body, () -> operations.createVisitor(body));
        var edit = Map.<String, Object>of("expectedVersion", 0);
        var statuses =
                race(
                        first,
                        second,
                        edit,
                        () -> operations.leave(visitor.get("id").toString(), edit));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(
                        db.count(
                                "SELECT version FROM visitor_registration WHERE id=?",
                                visitor.get("id")))
                .isEqualTo(1);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM audit_event WHERE business_type='VISITOR' AND"
                                        + " business_id=? AND action='VISITOR_LEAVE'",
                                visitor.get("id")))
                .isEqualTo(1);
    }

    List<Integer> race(Actor first, Actor second, Map<String, Object> body, Supplier<Object> work)
            throws Exception {
        var snapshots = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (Actor manager : List.of(first, second))
                tasks.add(
                        workers.submit(
                                () -> {
                                    try {
                                        command(
                                                manager,
                                                body,
                                                () -> {
                                                    snapshots.countDown();
                                                    await(snapshots);
                                                    return work.get();
                                                });
                                        return 200;
                                    } catch (ApiException e) {
                                        return e.status();
                                    }
                                }));
            return List.of(
                    tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void studentScopeIsRecheckedAfterWaitingForConcurrentTransfer() throws Exception {
        Actor mover = actor("manager1"), oldBuildingManager = actor("manager2");
        String suffix = Long.toString(System.nanoTime());
        String aBuilding = db.one("SELECT id FROM building WHERE code='A'").get("id").toString();
        String bBuilding = db.one("SELECT id FROM building WHERE code='B'").get("id").toString();
        var sourceBody =
                Map.<String, Object>of(
                        "buildingId",
                        bBuilding,
                        "roomNo",
                        "B" + suffix,
                        "floorNo",
                        1,
                        "capacity",
                        2);
        var targetBody =
                Map.<String, Object>of(
                        "buildingId",
                        aBuilding,
                        "roomNo",
                        "A" + suffix,
                        "floorNo",
                        1,
                        "capacity",
                        2);
        var source = command(mover, sourceBody, () -> housing.saveRoom(null, sourceBody));
        var target = command(mover, targetBody, () -> housing.saveRoom(null, targetBody));
        var student = student(mover, suffix + "S");
        var enrollBody =
                Map.<String, Object>of("studentId", student.get("id"), "roomId", source.get("id"));
        var residence = command(mover, enrollBody, () -> housing.checkIn(enrollBody));
        var transferBody =
                Map.<String, Object>of(
                        "targetRoomId", target.get("id"), "reason", "并发权限回归", "expectedVersion", 0);
        var edit =
                Map.<String, Object>of(
                        "studentNo",
                        student.get("studentNo"),
                        "name",
                        "并发回归学生",
                        "college",
                        "虚构学院",
                        "phone",
                        "13800000000",
                        "status",
                        "INACTIVE",
                        "expectedVersion",
                        0);
        var transferredButUncommitted = new CountDownLatch(1);
        var allowTransferCommit = new CountDownLatch(1);
        var editorPid = new java.util.concurrent.atomic.AtomicLong();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var transfer =
                    workers.submit(
                            () ->
                                    command(
                                            mover,
                                            transferBody,
                                            () -> {
                                                Object moved =
                                                        housing.move(
                                                                residence.get("id").toString(),
                                                                transferBody,
                                                                false);
                                                transferredButUncommitted.countDown();
                                                await(allowTransferCommit);
                                                return moved;
                                            }));
            await(transferredButUncommitted);
            var editResult =
                    workers.submit(
                            () -> {
                                try {
                                    command(
                                            oldBuildingManager,
                                            edit,
                                            () -> {
                                                if (OpenGaussTestDatabase.configured())
                                                    editorPid.set(
                                                            db.count("SELECT pg_backend_pid()"));
                                                return housing.saveStudent(
                                                        student.get("id").toString(), edit);
                                            });
                                    return 200;
                                } catch (ApiException e) {
                                    return e.status();
                                }
                            });
            try {
                boolean blockedOnStudent = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline) {
                    blockedOnStudent =
                            OpenGaussTestDatabase.configured()
                                    ? db
                                            .rows(
                                                    "SELECT query AS info FROM pg_stat_activity"
                                                            + " WHERE pid=? AND waiting=TRUE",
                                                    editorPid.get())
                                            .stream()
                                            .anyMatch(
                                                    row ->
                                                            Objects.toString(row.get("info"), "")
                                                                            .contains(
                                                                                    "FROM student"
                                                                                        + " WHERE"
                                                                                        + " id=")
                                                                    && Objects.toString(
                                                                                    row.get("info"),
                                                                                    "")
                                                                            .contains("FOR UPDATE"))
                                    : db.rows("SHOW FULL PROCESSLIST").stream()
                                            .anyMatch(
                                                    row -> {
                                                        String info =
                                                                Objects.toString(
                                                                        row.get("info"), "");
                                                        return info.contains(
                                                                        "FROM student WHERE id=")
                                                                && info.contains(
                                                                        student.get("id")
                                                                                .toString())
                                                                && info.contains("FOR UPDATE");
                                                    });
                    if (blockedOnStudent) break;
                    Thread.sleep(25);
                }
                assertThat(blockedOnStudent)
                        .as("editor reaches the actual database row lock before transfer commits")
                        .isTrue();
            } finally {
                allowTransferCommit.countDown();
            }
            assertThat(transfer.get(15, TimeUnit.SECONDS)).containsEntry("buildingId", aBuilding);
            assertThat(editResult.get(15, TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(db.one("SELECT status FROM student WHERE id=?", student.get("id")).get("status"))
                .isEqualTo("ACTIVE");
    }
}
