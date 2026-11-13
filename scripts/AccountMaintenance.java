import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local operator tool. It has no HTTP endpoint and never prints a credential. */
public class AccountMaintenance {
    static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(10);

    public static void main(String[] args) throws Exception {
        var options = options(args);
        if (options.containsKey("self-test")) {
            selfTest();
            return;
        }
        String url = requiredEnv("ACCOUNT_DB_URL");
        if (!url.startsWith("jdbc:opengauss://")) {
            throw new IllegalArgumentException("Maintenance requires the configured openGauss database");
        }
        try (var c = DriverManager.getConnection(url, requiredEnv("OG_USER"), requiredEnv("OG_PASSWORD"))) {
            try (var statement = c.createStatement()) {
                statement.execute("SET TIME ZONE 'UTC'");
            }
            operate(c, options);
        }
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Use named maintenance options");
            }
            String key = args[i].substring(2);
            if (key.equals("self-test")) {
                result.put(key, "true");
            } else if (++i < args.length) {
                result.put(key, args[i]);
            } else {
                throw new IllegalArgumentException("Missing option value");
            }
        }
        return result;
    }

    static void operate(Connection c, Map<String, String> o) throws Exception {
        String action = require(o, "action");
        if (!Set.of("status", "reset-password", "set-enabled", "set-buildings", "create-manager").contains(action)) {
            throw new IllegalArgumentException("Unknown maintenance action");
        }
        String username = require(o, "username");
        if (!username.matches("[A-Za-z0-9_.@-]{1,64}")) {
            throw new IllegalArgumentException("Invalid account name");
        }
        if (action.equals("status")) {
            try (var s = c.prepareStatement("SELECT id,role,enabled,account_version,must_change_password FROM sys_user WHERE username=?")) {
                s.setString(1, username);
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw new IllegalArgumentException("Account does not exist");
                    }
                    System.out.printf("{\"userId\":\"%s\",\"role\":\"%s\",\"enabled\":%s,\"accountVersion\":%d,\"mustChangePassword\":%s}%n",
                            r.getString(1), r.getString(2), r.getBoolean(3), r.getInt(4), r.getBoolean(5));
                }
            }
            return;
        }
        String reason = require(o, "reason");
        if (reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("An operator reason of 1–500 characters is required");
        }
        boolean credential = action.equals("reset-password") || action.equals("create-manager");
        Path delivery = credential ? deliveryPath(o) : null;
        String password = credential ? newPassword() : null;
        String encoded = credential ? PASSWORDS.encode(password) : null;
        Instant expires = Instant.now().plusSeconds(86400);
        boolean createdDelivery = false;
        c.setAutoCommit(false);
        try {
            long id;
            int version = 0;
            if (action.equals("create-manager")) {
                String name = require(o, "display-name");
                if (name.isBlank() || name.length() > 50) {
                    throw new IllegalArgumentException("Invalid display name");
                }
                try (var s = c.prepareStatement("INSERT INTO sys_user(username,password_hash,display_name,role,enabled,must_change_password,credential_expires_at) VALUES(?,?,?,'MANAGER',TRUE,TRUE,?)", new String[] {"id"})) {
                    s.setString(1, username);
                    s.setString(2, encoded);
                    s.setString(3, name);
                    s.setTimestamp(4, Timestamp.from(expires), utc());
                    s.executeUpdate();
                    try (var keys = s.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Account ID was not returned");
                        }
                        id = keys.getLong(1);
                    }
                }
                buildings(c, id, require(o, "building-ids"));
            } else {
                String role;
                try (var s = c.prepareStatement("SELECT id,role,account_version FROM sys_user WHERE username=? FOR UPDATE")) {
                    s.setString(1, username);
                    try (var r = s.executeQuery()) {
                        if (!r.next()) {
                            throw new IllegalArgumentException("Account does not exist");
                        }
                        id = r.getLong(1);
                        role = r.getString(2);
                        version = r.getInt(3);
                    }
                }
                if (version != Integer.parseInt(require(o, "expected-version"))) {
                    throw new IllegalArgumentException("Account version changed; inspect status and retry");
                }
                if (action.equals("reset-password")) {
                    update(c, "UPDATE sys_user SET password_hash=?,must_change_password=TRUE,credential_expires_at=?,account_version=account_version+1 WHERE id=?", encoded, Timestamp.from(expires), id);
                } else {
                    if (!role.equals("MANAGER")) {
                        throw new IllegalArgumentException("Manage student status through student records");
                    }
                    if (action.equals("set-enabled")) {
                        String enabled = require(o, "enabled");
                        if (!Set.of("true", "false").contains(enabled)) {
                            throw new IllegalArgumentException("Enabled must be true or false");
                        }
                        update(c, "UPDATE sys_user SET enabled=?,account_version=account_version+1 WHERE id=?", Boolean.parseBoolean(enabled), id);
                    } else {
                        buildings(c, id, require(o, "building-ids"));
                        update(c, "UPDATE sys_user SET account_version=account_version+1 WHERE id=?", id);
                    }
                }
                revoke(c, id);
                version++;
            }
            update(c, "INSERT INTO audit_event(operator_id,operator_name,action,business_type,business_id,note,changes,created_at) VALUES(0,'本机受控维护',?,'ACCOUNT',?,?,?,?)",
                    "MAINTENANCE_" + action.replace('-', '_').toUpperCase(Locale.ROOT), id, reason,
                    "{\"accountVersion\":" + version + "}", Timestamp.from(Instant.now()));
            if (credential) {
                createPrivate(delivery);
                createdDelivery = true;
                Files.writeString(delivery, "{\"username\":\"" + username + "\",\"temporaryPassword\":\"" + password
                        + "\",\"expiresAt\":\"" + expires + "\",\"mustChangePassword\":true}\n", StandardCharsets.UTF_8);
            }
            c.commit();
            System.out.printf("{\"userId\":\"%d\",\"accountVersion\":%d,\"changed\":true,\"credentialDeliveredPrivately\":%s}%n", id, version, credential);
        } catch (Exception failure) {
            c.rollback();
            if (createdDelivery) {
                Files.deleteIfExists(delivery);
            }
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static void buildings(Connection c, long id, String text) throws SQLException {
        String[] values = text.isBlank() ? new String[0] : text.split(",", -1);
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (!value.matches("[1-9][0-9]{0,17}")) {
                throw new IllegalArgumentException("Use comma-separated positive building IDs");
            }
            long building = Long.parseLong(value);
            try (var s = c.prepareStatement("SELECT id FROM building WHERE id=? AND enabled=TRUE")) {
                s.setLong(1, building);
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw new IllegalArgumentException("An enabled building was not found");
                    }
                }
            }
            ids.add(building);
        }
        update(c, "DELETE FROM manager_building WHERE manager_user_id=?", id);
        for (long building : ids) {
            update(c, "INSERT INTO manager_building(manager_user_id,building_id) VALUES(?,?)", id, building);
        }
    }

    static void revoke(Connection c, long id) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        update(c, "UPDATE auth_session SET revoked_at=?,last_seen_at=NULL WHERE user_id=? AND revoked_at IS NULL", now, id);
        update(c, "UPDATE field_work_session SET state='ENDED',active_user_id=NULL,ended_at=?,lease_expires_at=?,source_epoch=source_epoch+1,version=version+1 WHERE user_id=? AND state IN ('ACTIVE','PAUSED')", now, now, id);
        update(c, "UPDATE manager_context_snapshot SET source='NONE',building_id=NULL,floor_no=NULL,latitude=NULL,longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,motion='UNKNOWN',quality='LOW',expires_at=?,anchor_id=NULL,anchor_confirmed_at=NULL,context_version=context_version+1,explanation='账号维护后位置失效' WHERE user_id=?", now, id);
        update(c, "DELETE FROM mobility_pending_leg WHERE user_id=?", id);
        update(c, "UPDATE dispatch_manager SET position_building_id=NULL,position_floor_no=NULL,position_updated_at=NULL,version=version+1 WHERE user_id=?", id);
        update(c, "UPDATE dispatch_revision SET dirty=TRUE,data_revision=data_revision+1,reason='ACCOUNT_MAINTENANCE',updated_at=? WHERE id=1", now);
    }

    static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Timestamp value) {
                    s.setTimestamp(i + 1, value, utc());
                } else {
                    s.setObject(i + 1, values[i]);
                }
            }
            return s.executeUpdate();
        }
    }

    static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    static Path deliveryPath(Map<String, String> o) throws Exception {
        Path root = Path.of(require(o, "project-root")).toRealPath();
        Path allowed = root.resolve("data/credentials");
        Files.createDirectories(allowed);
        Path path = Path.of(require(o, "credential-file")).toAbsolutePath().normalize();
        if (!path.getParent().equals(allowed.toRealPath()) || Files.exists(path)) {
            throw new IllegalArgumentException("Use a new file directly under ignored data/credentials");
        }
        return path;
    }

    static void createPrivate(Path path) throws Exception {
        Files.createFile(path);
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) {
            Files.delete(path);
            throw new IllegalStateException("Private credential delivery is unsupported on this filesystem");
        }
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    static String newPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "DL-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String require(Map<String, String> o, String key) {
        String value = o.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }

    static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing local environment setting " + key);
        }
        return value;
    }

    static void selfTest() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:maintenance-time")) {
            try (var statement = c.createStatement()) {
                statement.execute("CREATE TABLE moment(observed_at TIMESTAMP(3))");
            }
            Instant expected = Instant.parse("2026-11-12T01:23:45.678Z");
            update(c, "INSERT INTO moment(observed_at) VALUES(?)", Timestamp.from(expected));
            try (var statement = c.createStatement(); var row = statement.executeQuery("SELECT observed_at FROM moment")) {
                if (!row.next() || !expected.equals(row.getTimestamp(1, utc()).toInstant())) {
                    throw new AssertionError("Maintenance timestamps must round-trip in UTC");
                }
            }
        }
        if (newPassword().equals(newPassword())) {
            throw new AssertionError("Credentials must be independent");
        }
        Path root = Files.createTempDirectory("dormlink-maintenance-test-");
        var o = new HashMap<String, String>();
        o.put("project-root", root.toString());
        o.put("credential-file", root.resolve("outside.json").toString());
        try {
            deliveryPath(o);
            throw new AssertionError("Out-of-scope credential path accepted");
        } catch (IllegalArgumentException expected) {
            // Trust-boundary rejection is the intended behavior.
        }
        Path target = root.resolve("data/credentials/temp.json");
        o.put("credential-file", target.toString());
        createPrivate(deliveryPath(o));
        if (Files.getFileAttributeView(target, AclFileAttributeView.class) != null
                && Files.getFileAttributeView(target, AclFileAttributeView.class).getAcl().size() != 1) {
            throw new AssertionError("Credential ACL is not owner-only");
        }
        try {
            deliveryPath(o);
            throw new AssertionError("Existing credential file accepted");
        } catch (IllegalArgumentException expected) {
            // Existing delivery cannot be silently overwritten.
        }
        Files.delete(target);
        Files.delete(target.getParent());
        Files.delete(root.resolve("data"));
        Files.delete(root);
        System.out.println("Credential generation, scope, ownership, overwrite and UTC safeguards passed");
    }
}
