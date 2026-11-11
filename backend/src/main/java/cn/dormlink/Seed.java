package cn.dormlink;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;

@Component
class Seed implements ApplicationRunner {
    final Db db;
    final AuthService auth;
    final boolean enabled;

    Seed(Db db, AuthService auth, @Value("${app.seed}") boolean enabled) {
        this.db = db;
        this.auth = auth;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || db.count("SELECT COUNT(*) FROM sys_user") > 0) {
            return;
        }
        String hash = auth.encode("Demo@123456");
        String m1 = user("manager1", "林老师", "MANAGER", hash),
                m2 = user("manager2", "周老师", "MANAGER", hash);
        String u1 = user("student1", "陈同学", "STUDENT", hash),
                u2 = user("student2", "李同学", "STUDENT", hash),
                u3 = user("student3", "王同学", "STUDENT", hash);
        String
                b1 =
                        db.insert(
                                "INSERT INTO building(code,name,floor_count) VALUES(?,?,?)",
                                "A",
                                "A栋",
                                6),
                b2 =
                        db.insert(
                                "INSERT INTO building(code,name,floor_count) VALUES(?,?,?)",
                                "B",
                                "B栋",
                                6);
        db.update("INSERT INTO manager_building VALUES(?,?)", m1, b1);
        db.update("INSERT INTO manager_building VALUES(?,?)", m1, b2);
        db.update("INSERT INTO manager_building VALUES(?,?)", m2, b2);
        String r1 = room(b1, "302", 3, 4), r2 = room(b1, "303", 3, 4), r3 = room(b2, "405", 4, 4);
        room(b2, "406", 4, 2);
        String s1 = student(u1, "20260001", "陈同学", m1), s2 = student(u2, "20260002", "李同学", m1);
        student(u3, "20260003", "王同学", m1);
        String h1 = reside(s1, r1, b1, "302", "A栋", m1), h2 = reside(s2, r3, b2, "405", "B栋", m1);
        String repair =
                db.insert(
                        "INSERT INTO"
                            + " repair_order(order_no,reporter_student_id,reporter_name,residence_id,room_id,room_no,building_id,building_name,title,description,category,priority,contact_name,contact_phone,status,submitted_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        "BX-DEMO-001",
                        s1,
                        "陈同学",
                        h1,
                        r1,
                        "302",
                        b1,
                        "A栋",
                        "洗手台下方管道漏水",
                        "关闭水龙头后仍持续滴水，请协助检查。",
                        "UTILITIES",
                        "NORMAL",
                        "陈同学",
                        "13800000001",
                        "SUBMITTED",
                        Timestamp.from(Instant.now().minusSeconds(3600)));
        db.insert(
                "INSERT INTO"
                    + " repair_event(order_id,to_status,action,operator_id,operator_name,note,created_at)"
                    + " VALUES(?,?,?,?,?,?,?)",
                repair,
                "SUBMITTED",
                "submit",
                u1,
                "陈同学",
                "已提交报修",
                Timestamp.from(Instant.now().minusSeconds(3600)));
        db.insert(
                "INSERT INTO"
                    + " hygiene_inspection(room_id,room_no,building_id,building_name,inspection_date,round,inspected_at,floor_score,desk_score,bed_score,bathroom_score,total_score,grade,issue_note,remediation_note,inspector_user_id,inspector_name)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r1,
                "302",
                b1,
                "A栋",
                LocalDate.now(ZoneId.of("Asia/Shanghai")),
                "ROUTINE",
                Timestamp.from(Instant.now().minusSeconds(1800)),
                23,
                22,
                24,
                22,
                91,
                "EXCELLENT",
                "",
                "保持整洁",
                m1,
                "林老师");
        db.insert(
                "INSERT INTO"
                    + " visitor_registration(visitor_name,phone,visited_student_id,student_name,room_id,room_no,building_id,building_name,purpose,arrived_at,operator_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "演示访客",
                "13900000001",
                s2,
                "李同学",
                r3,
                "405",
                b2,
                "B栋",
                "探访学生",
                Timestamp.from(Instant.now().minusSeconds(1200)),
                m1);
    }

    String user(String n, String name, String role, String hash) {
        return db.insert(
                "INSERT INTO sys_user(username,password_hash,display_name,role) VALUES(?,?,?,?)",
                n,
                hash,
                name,
                role);
    }

    String room(String b, String n, int f, int cap) {
        return db.insert(
                "INSERT INTO dorm_room(building_id,room_no,floor_no,capacity) VALUES(?,?,?,?)",
                b,
                n,
                f,
                cap);
    }

    String student(String u, String no, String name, String manager) {
        return db.insert(
                "INSERT INTO"
                    + " student(user_id,student_no,name,college,major,grade,class_name,phone,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                u,
                no,
                name,
                "信息工程学院",
                "软件工程",
                "2026级",
                "软件2601",
                no.endsWith("1") ? "13800000001" : "13800000002",
                manager);
    }

    String reside(String s, String r, String b, String no, String name, String m) {
        return db.insert(
                "INSERT INTO"
                    + " residence(student_id,room_id,building_id,room_no,building_name,checked_in_at,active_student_id,operator_id)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                s,
                r,
                b,
                no,
                name,
                Timestamp.from(Instant.now().minusSeconds(86400 * 30)),
                s,
                m);
    }
}
