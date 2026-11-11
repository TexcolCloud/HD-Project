package cn.dormlink.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.jdbc.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real JDBC/filesystem regressions for bounded cleanup and deletion failures. */
class FileCleanupTest {
    @Test
    void moreThanFiveHundredExpiredAssetsAreProcessedInBoundedPages() throws Exception {
        try (var fixture = new Fixture()) {
            for (int index = 0; index < 1001; index++) {
                fixture.asset("TEMP", "asset-" + index + ".jpg", false);
            }
            var largestPage = new AtomicInteger();
            doAnswer(invocation -> {
                var rows = (List<?>) invocation.callRealMethod();
                largestPage.accumulateAndGet(rows.size(), Math::max);
                return rows;
            }).when(fixture.db).rows(any(String.class), any(Object[].class));

            fixture.files.cleanup();
            assertThat(largestPage.get()).as("JDBC cleanup transfer must be bounded").isLessThanOrEqualTo(500);
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset")).isEqualTo(501);
            fixture.files.cleanup();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset")).isEqualTo(1);
            fixture.files.cleanup();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset")).isZero();
        }
    }

    @Test
    void failedPhysicalDeletionRetainsTombstoneWithoutStarvingLaterIdsOrDeletingBoundFiles() throws Exception {
        try (var fixture = new Fixture()) {
            String failed = fixture.asset("TEMP", "blocked.jpg", false);
            Files.writeString(fixture.root.resolve("blocked.jpg"), "original");
            Path thumbnail = fixture.root.resolve("blocked.jpg.thumb.jpg");
            Files.createDirectory(thumbnail);
            Files.writeString(thumbnail.resolve("blocker"), "force deletion failure");
            for (int index = 0; index < 500; index++) {
                fixture.asset("TEMP", "good-" + index + ".jpg", false);
            }
            String bound = fixture.asset("BOUND", "bound.jpg", true);
            Files.writeString(fixture.root.resolve("bound.jpg"), "historical original");
            Files.writeString(fixture.root.resolve("bound.jpg.thumb.jpg"), "historical thumbnail");

            fixture.files.cleanup();
            assertThat(fixture.db.one("SELECT state FROM file_asset WHERE id=?", failed).get("state"))
                    .isEqualTo("DELETED");
            assertThat(Files.exists(thumbnail.resolve("blocker"))).isTrue();
            fixture.files.cleanup();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset WHERE state='TEMP'")).isZero();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset")).isEqualTo(2);
            assertThat(fixture.db.one("SELECT state FROM file_asset WHERE id=?", bound).get("state"))
                    .isEqualTo("BOUND");
            assertThat(Files.readString(fixture.root.resolve("bound.jpg"))).isEqualTo("historical original");
            assertThat(Files.exists(fixture.root.resolve("bound.jpg.thumb.jpg"))).isTrue();

            Files.delete(thumbnail.resolve("blocker"));
            fixture.files.cleanup();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset WHERE id=?", failed)).isZero();
            assertThat(Files.exists(thumbnail)).isFalse();
            assertThat(fixture.db.count("SELECT COUNT(*) FROM file_asset")).isEqualTo(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("dormlink-cleanup-").toAbsolutePath().normalize();
        final Db db;
        final FileService files;
        final SingleConnectionDataSource source;

        Fixture() throws Exception {
            source = new SingleConnectionDataSource("jdbc:h2:mem:cleanup_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "", true);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            db = spy(new Db(new JdbcTemplate(source)));
            db.update("INSERT INTO sys_user(id,username,password_hash,display_name,role) VALUES(1,'fixture','unused','fixture','STUDENT')");
            files = new FileService(db, mock(AuthService.class), mock(BusinessSupport.class),
                    root.toString(), new DataSourceTransactionManager(source));
        }

        String asset(String state, String key, boolean bound) {
            return db.insert("INSERT INTO file_asset(owner_user_id,storage_key,original_name,mime,size_bytes,usage_type,state,business_type,business_id,created_at)"
                    + " VALUES(1,?,?, 'image/jpeg',1,'REPORT',?,?,?,?)", key, key, state,
                    bound ? "REPAIR" : null, bound ? 42 : null,
                    Timestamp.from(Instant.now().minusSeconds(2 * 86400)));
        }

        @Override
        public void close() throws Exception {
            Path expectedParent = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (!root.getParent().equals(expectedParent) || !root.getFileName().toString().startsWith("dormlink-cleanup-")) {
                throw new IllegalStateException("Unexpected test cleanup directory");
            }
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            source.destroy();
        }
    }
}
