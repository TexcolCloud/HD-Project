package cn.dormlink.shared.jdbc;

import cn.dormlink.identity.AuthService;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.Comparator;
import java.util.TimeZone;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Ordered, checksum-verified additive migrations. Existing tables are never rebuilt. */
@Component
@DependsOnDatabaseInitialization
public class SchemaMigrations implements InitializingBean {
    private static final Pattern FILE = Pattern.compile("V(\\d+)__(.+)\\.sql");
    private static final Pattern COLUMN =
            Pattern.compile("(?is)^ALTER TABLE ([a-z_]+) ADD COLUMN ([a-z_]+).*$");
    private static final Pattern INDEX =
            Pattern.compile("(?is)^CREATE (?:UNIQUE )?INDEX ([a-z_]+) ON ([a-z_]+).*$");
    private final DataSource source;
    private final String migrationLocation;

    public SchemaMigrations(
            DataSource source,
            @Value("${app.migration-location:db/migration/V2__dynamic_dispatch.sql}")
                    String migrationLocation) {
        this.source = source;
        this.migrationLocation = migrationLocation;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String directory = migrationLocation.substring(0, migrationLocation.lastIndexOf('/') + 1);
        Resource[] resources =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:" + directory + "V*__*.sql");
        var migrations =
                java.util.Arrays.stream(resources)
                        .map(Migration::from)
                        .sorted(Comparator.comparingInt(Migration::version))
                        .toList();
        if (migrations.isEmpty() || migrations.getFirst().version() != 2) {
            throw new IllegalStateException("Required historical migration V2 is missing");
        }
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY,"
                            + " description VARCHAR(100) NOT NULL, checksum CHAR(64) NOT NULL,"
                            + " applied_at TIMESTAMP(3) NOT NULL)");
            for (Migration migration : migrations) {
                apply(connection, migration);
            }
        }
    }

    private void apply(Connection connection, Migration migration) throws Exception {
        String script = migration.resource().getContentAsString(StandardCharsets.UTF_8);
        String checksum = AuthService.hash(script);
        try (var find =
                connection.prepareStatement(
                        "SELECT checksum FROM schema_migration WHERE version=?")) {
            find.setInt(1, migration.version());
            try (var row = find.executeQuery()) {
                if (row.next()) {
                    if (!checksum.equals(row.getString(1))) {
                        throw new IllegalStateException(
                                "Applied migration V" + migration.version() + " checksum changed");
                    }
                    return;
                }
            }
        }
        // Metadata guards allow an interrupted MySQL DDL migration to resume safely.
        try (var statement = connection.createStatement()) {
            for (String fragment : script.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
                String sql = fragment.trim();
                if (!sql.isEmpty() && !alreadyApplied(connection, sql)) {
                    statement.execute(sql);
                }
            }
        }
        if (migration.version() == 2) {
            try (var revision =
                    connection.prepareStatement(
                            "INSERT INTO dispatch_revision(id,updated_at) SELECT 1,? WHERE NOT"
                                    + " EXISTS(SELECT 1 FROM dispatch_revision WHERE id=1)")) {
                revision.setTimestamp(1, Timestamp.from(Instant.now()), utc());
                revision.executeUpdate();
            }
        }
        try (var insert =
                connection.prepareStatement(
                        "INSERT INTO schema_migration"
                                + "(version,description,checksum,applied_at) VALUES(?,?,?,?)")) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.description());
            insert.setString(3, checksum);
            insert.setTimestamp(4, Timestamp.from(Instant.now()), utc());
            insert.executeUpdate();
        }
    }

    private boolean alreadyApplied(Connection connection, String sql) throws Exception {
        var column = COLUMN.matcher(sql);
        if (column.matches()) {
            try (var columns =
                    connection
                            .getMetaData()
                            .getColumns(connection.getCatalog(), null, column.group(1), null)) {
                while (columns.next()) {
                    if (column.group(2).equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        var index = INDEX.matcher(sql);
        if (index.matches()) {
            try (var indexes =
                    connection
                            .getMetaData()
                            .getIndexInfo(
                                    connection.getCatalog(), null, index.group(2), false, false)) {
                while (indexes.next()) {
                    if (index.group(1).equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private record Migration(int version, String description, Resource resource) {
        static Migration from(Resource resource) {
            var name = FILE.matcher(resource.getFilename());
            if (!name.matches()) {
                throw new IllegalStateException(
                        "Invalid migration filename: " + resource.getFilename());
            }
            return new Migration(
                    Integer.parseInt(name.group(1)), name.group(2).replace('_', ' '), resource);
        }
    }
}
