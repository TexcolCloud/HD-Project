package cn.dormlink;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Small, replay-safe additive migration runner; never rebuilds existing business tables. */
@Component
@DependsOnDatabaseInitialization
class SchemaMigrations implements InitializingBean {
  private final DataSource source;

  SchemaMigrations(DataSource source) {
    this.source = source;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY, description"
              + " VARCHAR(100) NOT NULL, checksum CHAR(64) NOT NULL, applied_at TIMESTAMP(3) NOT"
              + " NULL)");
      String script =
          new ClassPathResource("db/migration/V2__dynamic_dispatch.sql")
              .getContentAsString(StandardCharsets.UTF_8);
      String checksum = AuthService.hash(script);
      try (var find =
          connection.prepareStatement("SELECT checksum FROM schema_migration WHERE version=2")) {
        var row = find.executeQuery();
        if (row.next()) {
          if (!checksum.equals(row.getString(1)))
            throw new IllegalStateException("Applied migration V2 checksum changed");
          return;
        }
      }
      var alter =
          Pattern.compile("(?i)^ALTER TABLE ([a-z_]+) ADD COLUMN ([a-z_]+).*$", Pattern.DOTALL);
      for (String fragment : script.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
        String sql = fragment.trim();
        if (sql.isEmpty()) continue;
        var match = alter.matcher(sql);
        if (match.matches()) {
          try (var columns =
              connection
                  .getMetaData()
                  .getColumns(connection.getCatalog(), null, match.group(1), match.group(2))) {
            if (columns.next()) continue;
          }
        }
        statement.execute(sql);
      }
      try (var revision =
          connection.prepareStatement(
              "INSERT INTO dispatch_revision(id,updated_at) SELECT 1,? WHERE NOT EXISTS(SELECT 1"
                  + " FROM dispatch_revision WHERE id=1)")) {
        revision.setTimestamp(1, Timestamp.from(Instant.now()));
        revision.executeUpdate();
      }
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO schema_migration(version,description,checksum,applied_at)"
                  + " VALUES(2,?,?,?)")) {
        insert.setString(1, "Dynamic multi-manager dispatch");
        insert.setString(2, checksum);
        insert.setTimestamp(3, Timestamp.from(Instant.now()));
        insert.executeUpdate();
      }
    }
  }
}
