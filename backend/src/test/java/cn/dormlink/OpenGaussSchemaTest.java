package cn.dormlink;

import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.jdbc.SchemaMigrations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class OpenGaussSchemaTest {
  @Test
  void pgSchemaAndMigrationPreserveChineseCharactersEmptyStringsAndReplaySafety() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:pg-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    new ResourceDatabasePopulator(new ClassPathResource("db/opengauss/schema.sql")).execute(source);
    var migrations = new SchemaMigrations(source, "db/opengauss/V2__dynamic_dispatch.sql");
    migrations.afterPropertiesSet();
    migrations.afterPropertiesSet();
    var db = new Db(new JdbcTemplate(source));
    String name = "寓".repeat(50);
    String building =
        db.insert("INSERT INTO building(code,name,floor_count) VALUES(?,?,?)", "PG", name, 6);
    String room =
        db.insert(
            "INSERT INTO dorm_room(building_id,room_no,floor_no,capacity) VALUES(?,?,?,?)",
            building,
            "101",
            1,
            4);
    assertThat(db.one("SELECT name FROM building WHERE id=?", building).get("name"))
        .isEqualTo(name);
    var saved = db.one("SELECT remark,enabled FROM dorm_room WHERE id=?", room);
    assertThat(saved.get("remark")).isEqualTo("");
    assertThat(saved.get("enabled")).isEqualTo(true);
    assertThat(db.count("SELECT COUNT(*) FROM schema_migration WHERE version=2")).isEqualTo(1);
    assertThat(db.count("SELECT COUNT(*) FROM dispatch_revision WHERE id=1")).isEqualTo(1);
  }
}
