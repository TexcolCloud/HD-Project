package cn.dormlink.housing;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class HousingService {
    final Db db;
    final AuthService auth;
    final BusinessSupport ops;
    static final String ROOM =
            "SELECT r.*,b.name building_name,(SELECT COUNT(*) FROM residence x WHERE x.room_id=r.id"
                + " AND x.checked_out_at IS NULL) occupied_count FROM dorm_room r JOIN building b"
                + " ON b.id=r.building_id ";
    static final String STUDENT =
            "SELECT s.*,h.room_id,h.room_no,h.building_name FROM student s LEFT JOIN residence h ON"
                    + " h.student_id=s.id AND h.checked_out_at IS NULL ";
    static final String RESIDENCE =
            "SELECT h.*,s.name student_name,s.student_no FROM residence h JOIN student s ON"
                    + " s.id=h.student_id ";

    HousingService(Db db, AuthService auth, BusinessSupport ops) {
        this.db = db;
        this.auth = auth;
        this.ops = ops;
    }

    Object buildings(Map<String, String> q) {
        var args = new ArrayList<Object>();
        String sql = "SELECT b.* FROM building b WHERE " + ops.scope("b.id");
        sql = BusinessSupport.search(sql, args, q, "b.name", "b.code");
        return ops.page(sql + " ORDER BY b.code", args, q);
    }

    Map<String, Object> building(String id) {
        var r = db.one("SELECT * FROM building WHERE id=?", id);
        ops.building(id);
        return r;
    }

    public Object saveBuilding(String id, Map<String, Object> b) {
        auth.current().requireManager();
        String code = Inputs.text(b, "code", 20, true), name = Inputs.text(b, "name", 50, true);
        int floors = Inputs.integer(b, "floorCount", 1, 100);
        boolean enabled = Inputs.bool(b, "enabled", true);
        if (id == null) {
            id =
                    db.insert(
                            "INSERT INTO building(code,name,floor_count,enabled) VALUES(?,?,?,?)",
                            code,
                            name,
                            floors,
                            enabled);
            db.update("INSERT INTO manager_building VALUES(?,?)", auth.current().id(), id);
        } else {
            var old = db.one("SELECT * FROM building WHERE id=? FOR UPDATE", id);
            ops.building(id);
            ops.version(old, b);
            if (!enabled
                    && db.count(
                                    "SELECT COUNT(*) FROM residence WHERE building_id=? AND"
                                            + " checked_out_at IS NULL",
                                    id)
                            > 0) {
                throw ApiException.conflict("楼栋还有在住学生，不能停用");
            }
            int changed =
                    db.update(
                            "UPDATE building SET"
                                + " code=?,name=?,floor_count=?,enabled=?,version=version+1 WHERE"
                                + " id=? AND version=?",
                            code,
                            name,
                            floors,
                            enabled,
                            id,
                            Inputs.version(b));
            if (changed != 1) {
                throw ApiException.conflict("楼栋已更新，请刷新后重试");
            }
        }
        ops.audit("SAVE_BUILDING", "BUILDING", id, "", Map.of("name", name, "enabled", enabled));
        return building(id);
    }

    Object rooms(Map<String, String> q) {
        q = new HashMap<>(q);
        if ("ENABLED".equals(q.get("status"))) {
            q.put("enabled", "true");
        }
        if ("DISABLED".equals(q.get("status"))) {
            q.put("enabled", "false");
        }
        var args = new ArrayList<Object>();
        String sql = ROOM + "WHERE " + ops.scope("r.building_id");
        sql = BusinessSupport.filter(sql, args, q, "buildingId", "r.building_id");
        sql = BusinessSupport.filter(sql, args, q, "floorNo", "r.floor_no");
        sql = BusinessSupport.search(sql, args, q, "r.room_no", "b.name");
        if (q.containsKey("enabled")) {
            args.add(Boolean.parseBoolean(q.get("enabled")));
            sql += " AND r.enabled=?";
        }
        String occupancy = q.get("occupancy");
        if ("FULL".equals(occupancy)) {
            sql +=
                    " AND (SELECT COUNT(*) FROM residence x WHERE x.room_id=r.id AND"
                            + " x.checked_out_at IS NULL)>=r.capacity";
        }
        if ("AVAILABLE".equals(occupancy)) {
            sql +=
                    " AND r.enabled=TRUE AND b.enabled=TRUE AND (SELECT COUNT(*) FROM residence x"
                            + " WHERE x.room_id=r.id AND x.checked_out_at IS NULL)<r.capacity";
        }
        return ops.page(sql + " ORDER BY r.building_id,r.room_no", args, q);
    }

    public Map<String, Object> room(String id) {
        var r = db.one(ROOM + "WHERE r.id=?", id);
        ops.building(r.get("buildingId"));
        return r;
    }

    Object currentRoom() {
        var a = auth.current();
        a.requireStudent();
        return db.find(
                ROOM
                        + "JOIN residence h ON h.room_id=r.id WHERE h.student_id=? AND"
                        + " h.checked_out_at IS NULL",
                a.studentId());
    }

    public Object saveRoom(String id, Map<String, Object> b) {
        auth.current().requireManager();
        String buildingId = Long.toString(Inputs.id(b, "buildingId"));
        ops.building(buildingId);
        var building = db.one("SELECT * FROM building WHERE id=? FOR UPDATE", buildingId);
        int floor = Inputs.integer(b, "floorNo", 1, (int) Db.number(building, "floorCount")),
                cap = Inputs.integer(b, "capacity", 1, 100);
        String number = Inputs.text(b, "roomNo", 20, true),
                remark = Inputs.text(b, "remark", 500, false);
        boolean enabled = Inputs.bool(b, "enabled", true);
        if (id == null) {
            id =
                    db.insert(
                            "INSERT INTO"
                                + " dorm_room(building_id,room_no,floor_no,capacity,enabled,remark)"
                                + " VALUES(?,?,?,?,?,?)",
                            buildingId,
                            number,
                            floor,
                            cap,
                            enabled,
                            remark);
        } else {
            var old = db.one("SELECT * FROM dorm_room WHERE id=? FOR UPDATE", id);
            ops.building(old.get("buildingId"));
            ops.version(old, b);
            long occupied =
                    db.rows(
                                    "SELECT id FROM residence WHERE room_id=? AND checked_out_at IS"
                                            + " NULL FOR UPDATE",
                                    id)
                            .size();
            if (cap < occupied || (!enabled && occupied > 0)) {
                throw ApiException.conflict("房间仍有入住，不能停用或缩减容量");
            }
            if (!buildingId.equals(old.get("buildingId"))
                    && db.count("SELECT COUNT(*) FROM residence WHERE room_id=?", id) > 0) {
                throw ApiException.conflict("已有入住历史的房间不能更改所属楼栋");
            }
            int changed =
                    db.update(
                            "UPDATE dorm_room SET"
                                + " building_id=?,room_no=?,floor_no=?,capacity=?,enabled=?,remark=?,version=version+1"
                                + " WHERE id=? AND version=?",
                            buildingId,
                            number,
                            floor,
                            cap,
                            enabled,
                            remark,
                            id,
                            Inputs.version(b));
            if (changed != 1) {
                throw ApiException.conflict("房间已更新，请刷新后重试");
            }
        }
        ops.audit(
                "SAVE_ROOM",
                "ROOM",
                id,
                "",
                Map.of("roomNo", number, "capacity", cap, "enabled", enabled));
        return room(id);
    }

    String studentScope() {
        var a = auth.current();
        a.requireManager();
        return "(h.id IS NULL OR " + ops.scope("h.building_id") + ")";
    }

    Object students(Map<String, String> q) {
        var args = new ArrayList<Object>();
        String sql = STUDENT + "WHERE " + studentScope();
        sql = BusinessSupport.search(sql, args, q, "s.name", "s.student_no", "h.room_no");
        sql = BusinessSupport.filter(sql, args, q, "buildingId", "h.building_id");
        if ("UNASSIGNED".equals(q.get("status"))) {
            sql += " AND h.id IS NULL";
        } else {
            sql = BusinessSupport.filter(sql, args, q, "status", "s.status");
        }
        var page = ops.page(sql + " ORDER BY s.student_no", args, q);
        for (var row : BusinessSupport.items(page)) {
            sanitizeStudent(row);
        }
        return page;
    }

    Map<String, Object> student(String id) {
        auth.current().requireManager();
        var s = db.one(STUDENT + "WHERE s.id=?", id);
        if (s.get("roomId") != null) {
            var room = db.one("SELECT building_id FROM dorm_room WHERE id=?", s.get("roomId"));
            ops.building(room.get("buildingId"));
        }
        sanitizeStudent(s);
        return s;
    }

    void sanitizeStudent(Map<String, Object> s) {
        if (s.get("roomId") == null && !auth.current().id().equals(s.get("createdBy"))) {
            s.put("phone", Inputs.mask(Objects.toString(s.get("phone"), "")));
        }
        s.remove("createdBy");
    }

    public Object saveStudent(String id, Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        String no = Inputs.text(b, "studentNo", 32, true),
                name = Inputs.text(b, "name", 50, true),
                college = Inputs.text(b, "college", 100, true),
                major = Inputs.text(b, "major", 100, false),
                grade = Inputs.text(b, "grade", 20, false),
                clazz = Inputs.text(b, "className", 100, false),
                phone = Inputs.phone(b, "phone"),
                status =
                        b.containsKey("status")
                                ? Inputs.choice(b, "status", "ACTIVE", "INACTIVE")
                                : "ACTIVE";
        if (id == null) {
            String uid =
                    db.insert(
                            "INSERT INTO sys_user(username,password_hash,display_name,role,enabled)"
                                    + " VALUES(?,?,?,?,?)",
                            no,
                            auth.encode("Demo@123456"),
                            name,
                            "STUDENT",
                            status.equals("ACTIVE"));
            id =
                    db.insert(
                            "INSERT INTO"
                                + " student(user_id,student_no,name,college,major,grade,class_name,phone,status,created_by)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                            uid,
                            no,
                            name,
                            college,
                            major,
                            grade,
                            clazz,
                            phone,
                            status,
                            a.id());
        } else {
            var raw = db.one("SELECT * FROM student WHERE id=? FOR UPDATE", id);
            var old = student(id);
            if (old.get("roomId") == null && !a.id().equals(raw.get("createdBy"))) {
                throw new ApiException(403, "FORBIDDEN", "未入住学生资料仅创建宿管可修改");
            }
            ops.version(raw, b);
            int changed =
                    db.update(
                            "UPDATE student SET"
                                + " student_no=?,name=?,college=?,major=?,grade=?,class_name=?,phone=?,status=?,version=version+1"
                                + " WHERE id=? AND version=?",
                            no,
                            name,
                            college,
                            major,
                            grade,
                            clazz,
                            phone,
                            status,
                            id,
                            Inputs.version(b));
            if (changed != 1) {
                throw ApiException.conflict("学生档案已更新，请刷新后重试");
            }
            db.update(
                    "UPDATE sys_user SET display_name=?,enabled=? WHERE id=?",
                    name,
                    status.equals("ACTIVE"),
                    raw.get("userId"));
            if (status.equals("INACTIVE")) {
                db.update(
                        "UPDATE auth_session SET revoked_at=? WHERE user_id=?",
                        Timestamp.from(Instant.now()),
                        raw.get("userId"));
            }
        }
        ops.audit(
                "SAVE_STUDENT",
                "STUDENT",
                id,
                "",
                Map.of("studentNo", no, "name", name, "status", status));
        return student(id);
    }

    Object residences(Map<String, String> q, boolean mine) {
        var args = new ArrayList<Object>();
        String sql = RESIDENCE + "WHERE ";
        if (mine) {
            auth.current().requireStudent();
            sql += "h.student_id=?";
            args.add(auth.current().studentId());
        } else {
            sql += ops.scope("h.building_id");
            sql = BusinessSupport.filter(sql, args, q, "studentId", "h.student_id");
        }
        sql = BusinessSupport.filter(sql, args, q, "buildingId", "h.building_id");
        sql = BusinessSupport.filter(sql, args, q, "roomId", "h.room_id");
        sql = BusinessSupport.search(sql, args, q, "s.name", "s.student_no", "h.room_no");
        if ("CURRENT".equals(q.get("status"))) {
            sql += " AND h.checked_out_at IS NULL";
        }
        return ops.page(sql + " ORDER BY h.id DESC", args, q);
    }

    public Object checkIn(Map<String, Object> b) {
        var a = auth.current();
        a.requireManager();
        String s = Long.toString(Inputs.id(b, "studentId")),
                r = Long.toString(Inputs.id(b, "roomId"));
        var student = db.one("SELECT * FROM student WHERE id=? FOR UPDATE", s);
        if (!"ACTIVE".equals(student.get("status"))) {
            throw ApiException.bad("学生档案已停用");
        }
        var destination = db.one("SELECT building_id FROM dorm_room WHERE id=?", r);
        db.one("SELECT id FROM building WHERE id=? FOR UPDATE", destination.get("buildingId"));
        var locked = db.one("SELECT * FROM dorm_room WHERE id=? FOR UPDATE", r);
        if (!Objects.equals(destination.get("buildingId"), locked.get("buildingId"))) {
            throw ApiException.conflict("房间所属楼栋已更新，请重新选择");
        }
        var room = room(r);
        checkCapacity(room);
        if (db.count(
                        "SELECT COUNT(*) FROM residence WHERE student_id=? AND checked_out_at IS"
                                + " NULL",
                        s)
                > 0) {
            throw ApiException.conflict("学生已经入住，请使用调宿");
        }
        Timestamp at = Inputs.time(b, "checkedInAt", Instant.now());
        Inputs.notFuture(at);
        if (db.count(
                        "SELECT COUNT(*) FROM residence WHERE student_id=? AND checked_out_at>?",
                        s,
                        at)
                > 0) {
            throw ApiException.bad("入住时间与历史记录重叠");
        }
        return insertResidence(s, room, at, "入住");
    }

    void checkCapacity(Map<String, Object> r) {
        if (!Boolean.TRUE.equals(r.get("enabled"))
                || db.count(
                                "SELECT COUNT(*) FROM building WHERE id=? AND enabled=TRUE",
                                r.get("buildingId"))
                        == 0) {
            throw ApiException.conflict("宿舍已停用");
        }
        var current =
                db.rows(
                        "SELECT id FROM residence WHERE room_id=? AND checked_out_at IS NULL FOR"
                                + " UPDATE",
                        r.get("id"));
        if (current.size() >= Db.number(r, "capacity")) {
            throw ApiException.conflict("宿舍已满，请选择其他房间");
        }
    }

    Map<String, Object> insertResidence(
            Object s, Map<String, Object> r, Timestamp at, String reason) {
        String id =
                db.insert(
                        "INSERT INTO"
                            + " residence(student_id,room_id,building_id,room_no,building_name,checked_in_at,active_student_id,reason,operator_id)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                        s,
                        r.get("id"),
                        r.get("buildingId"),
                        r.get("roomNo"),
                        r.get("buildingName"),
                        at,
                        s,
                        reason,
                        auth.current().id());
        ops.audit("CHECK_IN", "RESIDENCE", id, reason, Map.of("roomId", r.get("id")));
        return db.one(RESIDENCE + "WHERE h.id=?", id);
    }

    public Object move(String id, Map<String, Object> b, boolean checkout) {
        auth.current().requireManager();
        var old = db.one("SELECT * FROM residence WHERE id=?", id);
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE", old.get("studentId"));
        var ids = new TreeSet<Long>();
        ids.add(Db.number(old, "roomId"));
        if (!checkout) {
            ids.add(Inputs.id(b, "targetRoomId"));
        }
        var buildingIds = new TreeSet<Long>();
        for (Long roomId : ids) {
            buildingIds.add(
                    Db.number(
                            db.one("SELECT building_id FROM dorm_room WHERE id=?", roomId),
                            "buildingId"));
        }
        for (Long buildingId : buildingIds) {
            db.one("SELECT id FROM building WHERE id=? FOR UPDATE", buildingId);
        }
        for (Long roomId : ids) {
            db.one("SELECT id FROM dorm_room WHERE id=? FOR UPDATE", roomId);
        }
        for (Long roomId : ids) {
            if (!buildingIds.contains(
                    Db.number(
                            db.one("SELECT building_id FROM dorm_room WHERE id=?", roomId),
                            "buildingId"))) {
                throw ApiException.conflict("房间所属楼栋已更新，请重新选择");
            }
        }
        old = db.one("SELECT * FROM residence WHERE id=? FOR UPDATE", id);
        ops.building(old.get("buildingId"));
        ops.version(old, b);
        if (old.get("checkedOutAt") != null) {
            throw ApiException.conflict("该入住记录已经结束");
        }
        String reason = Inputs.text(b, "reason", 200, true);
        Map<String, Object> target = null;
        if (!checkout) {
            target = room(Long.toString(Inputs.id(b, "targetRoomId")));
            if (target.get("id").equals(old.get("roomId"))) {
                throw ApiException.bad("目标房间与当前房间相同");
            }
            checkCapacity(target);
        }
        Timestamp now = Timestamp.from(Instant.now());
        int changed =
                db.update(
                        "UPDATE residence SET"
                            + " checked_out_at=?,active_student_id=NULL,reason=?,version=version+1"
                            + " WHERE id=? AND version=?",
                        now,
                        reason,
                        id,
                        Inputs.version(b));
        if (changed != 1) {
            throw ApiException.conflict("入住记录已更新，请刷新后重试");
        }
        ops.audit(checkout ? "CHECK_OUT" : "TRANSFER_OUT", "RESIDENCE", id, reason, old);
        return checkout
                ? db.one(RESIDENCE + "WHERE h.id=?", id)
                : insertResidence(old.get("studentId"), target, now, reason);
    }
}
