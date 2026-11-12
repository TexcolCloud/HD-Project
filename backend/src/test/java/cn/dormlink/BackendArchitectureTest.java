package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dormlink.repairs.RepairService;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Enforces the module boundaries, including fully qualified references outside import statements.
 */
class BackendArchitectureTest {
    private static final Path SOURCES = Path.of("src/main/java/cn/dormlink");
    private static final Pattern REFERENCE =
            Pattern.compile("\\bcn\\.dormlink\\.(\\w+)(?:\\.(\\w+))?\\b");

    @Test
    void businessModulesHaveNoDependencyCycles() throws Exception {
        Map<String, Set<String>> dependencies = new TreeMap<>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = SOURCES.relativize(file);
                if (relative.getNameCount() < 2) {
                    continue;
                }
                String module = relative.getName(0).toString();
                if (module.equals("shared")) {
                    module += "." + relative.getName(1);
                }
                Set<String> targets = dependencies.computeIfAbsent(module, key -> new TreeSet<>());
                var references = REFERENCE.matcher(Files.readString(file));
                while (references.find()) {
                    String target = references.group(1);
                    if (target.equals("shared")) {
                        target += "." + references.group(2);
                    }
                    if (!target.equals(module)) {
                        targets.add(target);
                    }
                }
            }
        }
        List<String> cycles = new ArrayList<>();
        for (String module : dependencies.keySet()) {
            findCycles(module, module, dependencies, new LinkedHashSet<>(), cycles);
        }
        assertThat(cycles).as("business dependencies must point in one direction").isEmpty();
    }

    private void findCycles(
            String start,
            String current,
            Map<String, Set<String>> dependencies,
            LinkedHashSet<String> path,
            List<String> cycles) {
        path.add(current);
        for (String target : dependencies.getOrDefault(current, Set.of())) {
            if (target.equals(start)) {
                cycles.add(String.join(" -> ", path) + " -> " + start);
            } else if (!path.contains(target)) {
                findCycles(start, target, dependencies, path, cycles);
            }
        }
        path.remove(current);
    }

    @Test
    void sharedInfrastructureDoesNotDependOnBusinessModules() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String area : List.of("jdbc", "api", "crypto")) {
            Path directory = SOURCES.resolve("shared").resolve(area);
            if (!Files.exists(directory)) {
                continue;
            }
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    var references = REFERENCE.matcher(Files.readString(file));
                    while (references.find()) {
                        if (!references.group(1).equals("shared")) {
                            violations.add(SOURCES.relativize(file) + ": " + references.group());
                        }
                    }
                }
            }
        }
        assertThat(violations)
                .as("generic infrastructure cannot require an authenticated domain")
                .isEmpty();
    }

    @Test
    void repairModuleDoesNotExposeUntypedDatabaseRows() {
        assertThat(
                        Arrays.stream(RepairService.class.getDeclaredMethods())
                                .filter(method -> Modifier.isPublic(method.getModifiers()))
                                .filter(
                                        method ->
                                                Map.class.isAssignableFrom(method.getReturnType()))
                                .map(method -> method.getName())
                                .toList())
                .as("cross-module repair reads use explicit snapshots")
                .isEmpty();
    }
}
