package cn.dormlink;

import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;

/** Opt-in real-database runs reuse the same behavior tests and require isolated test databases. */
class OpenGaussTestDatabase implements ActiveProfilesResolver {
    static boolean configured() {
        return System.getenv("OPENGAUSS_TEST_URL") != null
                && !System.getenv("OPENGAUSS_TEST_URL").isBlank();
    }

    @Override
    public String[] resolve(Class<?> testClass) {
        return new String[] {
            configured() ? "opengauss" : testClass == MySqlConcurrencyTest.class ? "mysql" : "local"
        };
    }

    static void configure(DynamicPropertyRegistry registry, String suite) {
        if (!configured()) return;
        String template = System.getenv("OPENGAUSS_TEST_URL");
        if (!template.contains("{suite}"))
            throw new IllegalArgumentException(
                    "OPENGAUSS_TEST_URL must use an isolated {suite}_test database template");
        String url = template.replace("{suite}", suite);
        if (!url.startsWith("jdbc:opengauss://")
                || !URI.create(url.substring(5)).getPath().matches("/dormlink_[a-z0-9_]+_test"))
            throw new IllegalArgumentException("Use isolated dormlink_*_test openGauss databases");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("OPENGAUSS_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("OPENGAUSS_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.opengauss.Driver");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/opengauss/schema.sql");
        registry.add("app.migration-location", () -> "db/opengauss/V2__dynamic_dispatch.sql");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.datasource.hikari.data-source-properties.use_boolean", () -> "true");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET TIME ZONE 'UTC'");
    }
}
