import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** One-shot, read-only-source database migration. Run through migrate-database.ps1. */
public class MigrateDatabase {
  static final List<String> TABLES =
      List.of(
          "sys_user",
          "building",
          "manager_building",
          "student",
          "dorm_room",
          "residence",
          "visitor_registration",
          "repair_order",
          "repair_event",
          "hygiene_inspection",
          "file_asset",
          "audit_event",
          "idempotency_record",
          "ai_invocation",
          "auth_session",
          "dispatch_manager",
          "dispatch_revision",
          "dispatch_task",
          "dispatch_plan",
          "dispatch_event",
          "dispatch_travel",
          "schema_migration");
  static String stage = "configuration";

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      selfTest();
      return;
    }
    boolean checkOnly = args.length == 3 && args[0].equals("--check-only");
    int offset = checkOnly ? 1 : 0;
    if (args.length != offset + 2) throw new IllegalArgumentException("Run migrate-database.ps1");
    Path root = Path.of(args[offset]).toAbsolutePath().normalize();
    Path reportPath = Path.of(args[offset + 1]).toAbsolutePath().normalize();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("startedAt", Instant.now().toString());
    report.put("mode", checkOnly ? "CHECK_ONLY" : "MIGRATE");
    report.put("sourceReadOnly", true);
    try {
      migrate(root, checkOnly, report);
      report.put("passed", true);
    } catch (Exception failure) {
      report.put("passed", false);
      report.put("failureStage", stage);
      report.put("failureType", failure.getClass().getSimpleName());
      if (failure instanceof SQLException sql) report.put("sqlState", sql.getSQLState());
      if (failure instanceof IllegalStateException) report.put("reason", failure.getMessage());
      report.put("finishedAt", Instant.now().toString());
      saveReport(reportPath, report);
      // Driver exceptions can contain complete row values. Never log them or credentials.
      System.err.println(
          "Migration refused/failed at " + stage + "; inspect the value-free report.");
      System.exit(1);
    }
    report.put("finishedAt", Instant.now().toString());
    saveReport(reportPath, report);
    System.out.println(
        checkOnly
            ? "PASS migration preflight; no target changes"
            : "PASS database migration and typed-content comparison");
  }

  static void migrate(Path root, boolean checkOnly, Map<String, Object> report) throws Exception {
    String database = required("OG_DATABASE");
    if (!database.matches("dormlink(?:_[a-z0-9_]+)?"))
      throw new IllegalStateException("TARGET_DATABASE_MUST_BE_PROJECT_SPECIFIC");
    String sourceUrl =
        "jdbc:mysql://"
            + env("MYSQL_HOST", "127.0.0.1")
            + ":"
            + env("MYSQL_PORT", "13306")
            + "/"
            + required("MYSQL_DATABASE")
            + "?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false";
    String targetUrl =
        "jdbc:opengauss://"
            + required("OG_HOST")
            + ":"
            + env("OG_PORT", "5432")
            + "/"
            + database
            + "?currentSchema=public&use_boolean=true&stringtype=unspecified&connectTimeout=10&socketTimeout=120";
    if (!env("OG_SSLMODE", "").isEmpty()) targetUrl += "&sslmode=" + required("OG_SSLMODE");
    stage = "connect-source";
    try (var source =
        DriverManager.getConnection(
            sourceUrl, required("MYSQL_USER"), required("MYSQL_PASSWORD"))) {
      source.createStatement().execute("SET time_zone='+00:00'");
      source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      source.setReadOnly(true);
      source.setAutoCommit(false);
      stage = "connect-target";
      try (var target =
          DriverManager.getConnection(targetUrl, required("OG_USER"), required("OG_PASSWORD"))) {
        target.createStatement().execute("SET TIME ZONE 'UTC'");
        String version = scalarString(target, "SELECT version()");
        if (!version.toLowerCase(Locale.ROOT).contains("opengauss"))
          throw new IllegalStateException("TARGET_IS_NOT_OPENGAUSS");
        if (!scalarString(target, "SHOW sql_compatibility").equalsIgnoreCase("PG"))
          throw new IllegalStateException("TARGET_REQUIRES_PG_COMPATIBILITY");
        report.put("targetProduct", "openGauss");
        report.put("targetCompatibility", "PG");
        stage = "validate-source-schema";
        Set<String> sourceTables =
            tableNames(
                source,
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()");
        if (!sourceTables.equals(new HashSet<>(TABLES)))
          throw new IllegalStateException("SOURCE_TABLE_SET_DIFFERS_FROM_SUPPORTED_V2");
        validateMigration(
            source,
            root.resolve("backend/src/main/resources/db/migration/V2__dynamic_dispatch.sql"));
        report.put("attachments", verifyAttachments(source, root));
        var nextIds = sourceNextIds(source);
        Set<String> targetTables =
            tableNames(
                target,
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND"
                    + " table_type='BASE TABLE'");
        if (!new HashSet<>(TABLES).containsAll(targetTables))
          throw new IllegalStateException("TARGET_CONTAINS_UNRELATED_TABLES");
        if (!targetTables.isEmpty() && !targetTables.equals(new HashSet<>(TABLES)))
          throw new IllegalStateException("TARGET_SCHEMA_IS_PARTIAL");
        if (checkOnly) {
          if (!targetTables.isEmpty()) {
            validateTargetEmpty(target);
            validateMigration(
                target,
                root.resolve("backend/src/main/resources/db/opengauss/V2__dynamic_dispatch.sql"));
          }
          var counts = new LinkedHashMap<String, Long>();
          for (String table : TABLES)
            counts.put(table, scalarLong(source, "SELECT COUNT(*) FROM " + table));
          report.put("sourceRows", counts);
          report.put("targetNeedsInitialization", targetTables.isEmpty());
          report.put("sourceAutoIncrementColumns", nextIds.size());
          report.put("targetChanged", false);
          source.rollback();
          return;
        }
        target.setAutoCommit(false);
        try {
          stage = "initialize-target-schema";
          initialize(target, root);
          // One-shot migration: root stops the API before cutover. Locks also reject competing
          // writers.
          target
              .createStatement()
              .execute(
                  "LOCK TABLE " + String.join(",", TABLES) + " IN ACCESS EXCLUSIVE MODE NOWAIT");
          stage = "validate-target-empty";
          validateTargetEmpty(target);
          validateMigration(
              target,
              root.resolve("backend/src/main/resources/db/opengauss/V2__dynamic_dispatch.sql"));
          var checks = new ArrayList<Map<String, Object>>();
          for (String table : TABLES) {
            if (table.equals("schema_migration")) continue;
            stage = "copy-and-compare:" + table;
            checks.add(copyTable(source, target, table));
          }
          stage = "sequence-restoration";
          var sequences = new ArrayList<Map<String, Object>>();
          for (var entry : nextIds.entrySet()) {
            String table = entry.getKey();
            long next =
                Math.max(
                    entry.getValue(),
                    scalarLong(target, "SELECT COALESCE(MAX(id),0)+1 FROM " + table));
            String sequence;
            try (var query = target.prepareStatement("SELECT pg_get_serial_sequence(?, 'id')")) {
              query.setString(1, "public." + table);
              try (var rows = query.executeQuery()) {
                rows.next();
                sequence = rows.getString(1);
              }
            }
            if (sequence == null)
              throw new IllegalStateException("MISSING_TARGET_SEQUENCE:" + table);
            // setval is not transactional; only unused target sequences are changed, after all row
            // checks.
            try (var update = target.prepareStatement("SELECT setval(?::regclass, ?, false)")) {
              update.setString(1, sequence);
              update.setLong(2, next);
              update.executeQuery().close();
            }
            sequences.add(
                Map.of("table", table, "nextValue", next, "sourceNextValue", entry.getValue()));
          }
          report.put("tables", checks);
          report.put(
              "schemaMigration",
              Map.of(
                  "sourceRows",
                  1,
                  "targetRows",
                  1,
                  "targetChecksumValidated",
                  true,
                  "sourceChecksumCopied",
                  false));
          report.put("sequences", sequences);
          stage = "commit-target";
          target.commit();
          source.rollback();
          report.put("targetChanged", true);
          report.put("typedContentMatched", true);
        } catch (Exception failure) {
          target.rollback();
          source.rollback();
          throw failure;
        }
      }
    }
  }

  static void initialize(Connection target, Path root) throws Exception {
    executeScript(target, root.resolve("backend/src/main/resources/db/opengauss/schema.sql"));
    target
        .createStatement()
        .execute(
            "CREATE TABLE IF NOT EXISTS schema_migration(version INT PRIMARY KEY,description"
                + " VARCHAR(100) NOT NULL,checksum CHAR(64) NOT NULL,applied_at TIMESTAMP(3) NOT"
                + " NULL)");
    Path migration =
        root.resolve("backend/src/main/resources/db/opengauss/V2__dynamic_dispatch.sql");
    if (scalarLong(target, "SELECT COUNT(*) FROM schema_migration") == 0) {
      executeScript(target, migration);
      try (var insert = target.prepareStatement("INSERT INTO schema_migration VALUES(2,?,?,?)")) {
        insert.setString(1, "Dynamic multi-manager dispatch");
        insert.setString(2, checksum(migration));
        insert.setTimestamp(3, Timestamp.from(Instant.now()), utc());
        insert.executeUpdate();
      }
    }
  }

  static void executeScript(Connection target, Path path) throws Exception {
    String script = Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
    try (var statement = target.createStatement()) {
      for (String fragment : script.split(";"))
        if (!fragment.isBlank()) statement.execute(fragment.trim());
    }
  }

  static void validateMigration(Connection connection, Path path) throws Exception {
    try (var rows =
        connection
            .createStatement()
            .executeQuery("SELECT version,checksum FROM schema_migration")) {
      if (!rows.next()
          || rows.getInt(1) != 2
          || !rows.getString(2).equals(checksum(path))
          || rows.next()) throw new IllegalStateException("MIGRATION_V2_CHECKSUM_NOT_CURRENT");
    }
  }

  static void validateTargetEmpty(Connection target) throws SQLException {
    requireEmpty(
        target,
        TABLES.stream()
            .filter(t -> !t.equals("schema_migration") && !t.equals("dispatch_revision"))
            .toList());
    long count = scalarLong(target, "SELECT COUNT(*) FROM dispatch_revision");
    if (count > 1
        || count == 1
            && scalarLong(
                    target,
                    "SELECT COUNT(*) FROM dispatch_revision WHERE id=1 AND revision=0 AND"
                        + " data_revision=0 AND dirty=TRUE AND reason='INITIAL'")
                != 1) throw new IllegalStateException("TARGET_HAS_EXISTING_DISPATCH_STATE");
  }

  static Map<String, Long> sourceNextIds(Connection source) throws SQLException {
    var next = new LinkedHashMap<String, Long>();
    try (var rows =
        source
            .createStatement()
            .executeQuery(
                "SELECT table_name,auto_increment FROM information_schema.tables WHERE"
                    + " table_schema=DATABASE() AND auto_increment IS NOT NULL ORDER BY"
                    + " table_name")) {
      while (rows.next()) next.put(rows.getString(1), rows.getLong(2));
    }
    return next;
  }

  static Map<String, Object> verifyAttachments(Connection source, Path root) throws Exception {
    String configured = env("FILE_STORAGE", "data/uploads");
    Path storage = Path.of(configured);
    if (!storage.isAbsolute()) storage = root.resolve("backend").resolve(storage);
    storage = storage.toAbsolutePath().normalize();
    int live = 0, deleted = 0;
    try (var rows =
        source
            .createStatement()
            .executeQuery("SELECT storage_key,size_bytes,state FROM file_asset")) {
      while (rows.next()) {
        if (rows.getString(3).equals("DELETED")) {
          deleted++;
          continue;
        }
        Path file = storage.resolve(rows.getString(1)).normalize();
        if (!file.startsWith(storage)
            || !Files.isRegularFile(file)
            || Files.size(file) != rows.getLong(2))
          throw new IllegalStateException("LIVE_ATTACHMENT_MISSING_OR_SIZE_CHANGED");
        live++;
      }
    }
    return Map.of(
        "liveFilesVerified", live, "deletedMetadataPreserved", deleted, "filesMoved", false);
  }

  static Set<String> tableNames(Connection connection, String query) throws SQLException {
    var names = new HashSet<String>();
    try (var rows = connection.createStatement().executeQuery(query)) {
      while (rows.next()) names.add(rows.getString(1));
    }
    return names;
  }

  static long scalarLong(Connection connection, String sql) throws SQLException {
    try (var rows = connection.createStatement().executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  static String scalarString(Connection connection, String sql) throws SQLException {
    try (var rows = connection.createStatement().executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("MISSING_ENVIRONMENT:" + key);
    return value;
  }

  static String env(String key, String fallback) {
    return System.getenv().getOrDefault(key, fallback);
  }

  static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  static String checksum(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  static void saveReport(Path path, Map<String, Object> report) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, json(report) + "\n", StandardCharsets.UTF_8);
  }

  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Map<?, ?> map)
      return "{"
          + String.join(
              ",",
              map.entrySet().stream()
                  .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue()))
                  .toList())
          + "}";
    if (value instanceof Collection<?> list)
      return "[" + String.join(",", list.stream().map(MigrateDatabase::json).toList()) + "]";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 32) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    return out.append('"').toString();
  }

  static void requireEmpty(Connection target, List<String> tables) throws SQLException {
    try (var sql = target.createStatement()) {
      for (String table : tables) {
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
          rows.next();
          if (rows.getLong(1) != 0) throw new IllegalStateException("TARGET_NOT_EMPTY:" + table);
        }
      }
    }
  }

  static void selfTest() throws Exception {
    try (var target =
        DriverManager.getConnection("jdbc:h2:mem:migration_guard;MODE=PostgreSQL", "sa", "")) {
      try (var sql = target.createStatement()) {
        sql.execute("CREATE TABLE migration_probe(id BIGINT PRIMARY KEY)");
        sql.execute("INSERT INTO migration_probe VALUES(7)");
      }
      boolean refused = false;
      try {
        requireEmpty(target, List.of("migration_probe"));
      } catch (IllegalStateException expected) {
        refused = expected.getMessage().equals("TARGET_NOT_EMPTY:migration_probe");
      }
      if (!refused)
        throw new AssertionError("A non-empty migration target must be refused before writes");
      System.out.println("PASS non-empty target is refused");
    }
    try (var source =
            DriverManager.getConnection("jdbc:h2:mem:migration_source;MODE=MySQL", "sa", "");
        var target =
            DriverManager.getConnection("jdbc:h2:mem:migration_target;MODE=PostgreSQL", "sa", "")) {
      String ddl =
          "CREATE TABLE migration_probe(id BIGINT PRIMARY KEY,enabled BOOLEAN,seen_at"
              + " TIMESTAMP(3),note VARCHAR(30),inspection_date DATE)";
      source.createStatement().execute(ddl);
      target.createStatement().execute(ddl);
      try (var insert = source.prepareStatement("INSERT INTO migration_probe VALUES(?,?,?,?,?)")) {
        insert.setLong(1, 9007199254740993L);
        insert.setBoolean(2, false);
        insert.setTimestamp(
            3,
            Timestamp.from(Instant.parse("2026-11-07T00:15:25.123Z")),
            Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        insert.setString(4, "");
        insert.setDate(
            5, new java.sql.Date(Instant.parse("2026-11-07T00:00:00Z").toEpochMilli()), utc());
        insert.executeUpdate();
        insert.setLong(1, 9007199254740994L);
        insert.setObject(2, null);
        insert.setObject(3, null);
        insert.setObject(4, null);
        insert.setObject(5, null);
        insert.executeUpdate();
      }
      copyTable(source, target, "migration_probe");
      try (var rows =
          target.createStatement().executeQuery("SELECT * FROM migration_probe ORDER BY id")) {
        if (!rows.next()) throw new AssertionError("Migration must copy the complete typed row");
        if (rows.getLong("id") != 9007199254740993L
            || rows.getBoolean("enabled")
            || !rows.getString("note").equals("")
            || !rows.getTimestamp("seen_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                .toInstant()
                .equals(Instant.parse("2026-11-07T00:15:25.123Z"))
            || !rows.getString("inspection_date").equals("2026-11-07"))
          throw new AssertionError(
              "Migration must preserve exact IDs, false, empty strings, dates and UTC"
                  + " milliseconds");
        if (!rows.next()
            || rows.getObject("enabled") != null
            || rows.getObject("seen_at") != null
            || rows.getObject("note") != null
            || rows.getObject("inspection_date") != null
            || rows.next()) throw new AssertionError("Migration must preserve nulls and row count");
      }
      System.out.println(
          "PASS IDs, booleans, empty/null strings and UTC milliseconds are preserved");
    }
    saveReport(
        Path.of("output/acceptance/migration-tool-tests.json").toAbsolutePath(),
        new LinkedHashMap<>(
            Map.of(
                "verifiedAt",
                Instant.now().toString(),
                "passed",
                true,
                "engine",
                "H2 in-memory JDBC",
                "checks",
                List.of(
                    "non-empty-target-refused-before-copy",
                    "bigint-boolean-empty-null-date-utc-millisecond-copy"),
                "realOpenGaussVerified",
                false)));
  }

  record Column(String name, int type) {}

  static List<Column> columns(Connection connection, String table) throws SQLException {
    var result = new ArrayList<Column>();
    try (var rows =
        connection.createStatement().executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
      var metadata = rows.getMetaData();
      for (int i = 1; i <= metadata.getColumnCount(); i++)
        result.add(
            new Column(
                metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), metadata.getColumnType(i)));
    }
    return result;
  }

  static String ordering(String table) {
    return switch (table) {
      case "manager_building" -> "manager_user_id,building_id";
      case "dispatch_travel" -> "from_building_id,to_building_id";
      case "dispatch_manager" -> "user_id";
      case "dispatch_plan" -> "revision";
      default -> "id";
    };
  }

  static Object value(ResultSet rows, int index, int type) throws SQLException {
    if (rows.getObject(index) == null) return null;
    return switch (type) {
      case Types.BOOLEAN, Types.BIT -> rows.getBoolean(index);
      case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rows.getLong(index);
      case Types.NUMERIC, Types.DECIMAL -> rows.getBigDecimal(index).stripTrailingZeros();
      case Types.TIMESTAMP -> rows.getTimestamp(index, utc());
      case Types.DATE -> rows.getDate(index, utc());
      default -> rows.getString(index);
    };
  }

  static void parameter(PreparedStatement insert, int index, Column column, Object value)
      throws SQLException {
    if (value == null) insert.setNull(index, column.type());
    else if (value instanceof Timestamp time) insert.setTimestamp(index, time, utc());
    else if (value instanceof java.sql.Date date) insert.setDate(index, date, utc());
    else insert.setObject(index, value);
  }

  static final class Contents {
    final MessageDigest hash;
    final DataOutputStream data;
    long rows, nulls, booleans, timestamps, dates, emptyStrings;

    Contents() throws NoSuchAlgorithmException {
      hash = MessageDigest.getInstance("SHA-256");
      data = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash));
    }

    void add(Object value) throws IOException {
      if (value == null) {
        nulls++;
        data.writeInt(-1);
        return;
      }
      if (value instanceof Boolean) booleans++;
      if (value instanceof Timestamp) timestamps++;
      if (value instanceof java.sql.Date) dates++;
      if (value.equals("")) emptyStrings++;
      String normalized =
          value instanceof Timestamp t
              ? t.toInstant().toString()
              : value instanceof java.sql.Date d
                  ? Instant.ofEpochMilli(d.getTime())
                      .atOffset(java.time.ZoneOffset.UTC)
                      .toLocalDate()
                      .toString()
                  : value.toString();
      byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
      data.writeInt(bytes.length);
      data.write(bytes);
    }

    Map<String, Long> counts() {
      return Map.of(
          "rows",
          rows,
          "nullValues",
          nulls,
          "booleanValues",
          booleans,
          "utcTimestampValues",
          timestamps,
          "dateValues",
          dates,
          "emptyStrings",
          emptyStrings);
    }
  }

  static Map<String, Object> copyTable(Connection source, Connection target, String table)
      throws Exception {
    List<Column> columns = columns(target, table);
    if (!new HashSet<>(columns.stream().map(Column::name).toList())
        .equals(new HashSet<>(columns(source, table).stream().map(Column::name).toList())))
      throw new IllegalStateException("COLUMN_SET_MISMATCH:" + table);
    String names = String.join(",", columns.stream().map(Column::name).toList());
    String select = "SELECT " + names + " FROM " + table + " ORDER BY " + ordering(table);
    boolean replaceInitialRevision =
        table.equals("dispatch_revision")
            && scalarLong(target, "SELECT COUNT(*) FROM dispatch_revision") == 1;
    String write =
        replaceInitialRevision
            ? "UPDATE dispatch_revision SET "
                + String.join(
                    ",",
                    columns.stream()
                        .filter(c -> !c.name().equals("id"))
                        .map(c -> c.name() + "=?")
                        .toList())
                + " WHERE id=?"
            : "INSERT INTO "
                + table
                + "("
                + names
                + ") VALUES("
                + String.join(",", Collections.nCopies(columns.size(), "?"))
                + ")";
    Contents original = new Contents();
    try (var rows = source.createStatement().executeQuery(select);
        var insert = target.prepareStatement(write)) {
      while (rows.next()) {
        Object id = null;
        int parameter = 1;
        for (int i = 0; i < columns.size(); i++) {
          Column column = columns.get(i);
          Object value = value(rows, i + 1, column.type());
          original.add(value);
          if (replaceInitialRevision && column.name().equals("id")) id = value;
          else parameter(insert, parameter++, column, value);
        }
        if (replaceInitialRevision) insert.setObject(parameter, id);
        if (insert.executeUpdate() != 1)
          throw new IllegalStateException("UNEXPECTED_INSERT_COUNT:" + table);
        original.rows++;
      }
    }
    Contents copied = new Contents();
    try (var rows = target.createStatement().executeQuery(select)) {
      while (rows.next()) {
        for (int i = 0; i < columns.size(); i++)
          copied.add(value(rows, i + 1, columns.get(i).type()));
        copied.rows++;
      }
    }
    if (!original.counts().equals(copied.counts())
        || !Arrays.equals(original.hash.digest(), copied.hash.digest()))
      throw new IllegalStateException("COPIED_CONTENT_MISMATCH:" + table);
    return Map.of(
        "table",
        table,
        "sourceRows",
        original.rows,
        "targetRows",
        copied.rows,
        "typedContentMatched",
        true,
        "valuesChecked",
        copied.counts());
  }
}
