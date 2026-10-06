package com.springaimcpservercommon.persistence.unit;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway migration sequence without a database. Two pull requests that each add "the next" migration merge without a
 * textual conflict (different file names) yet make every start-up fail with "Found more than one migration with version N" — this
 * happened on main with V13 (chat UI state and the rule-engine lifecycle). The test turns that into a build failure of the second
 * merge candidate, where it can still be renumbered.
 */
class MigrationFilesTest {

    private static final Pattern NAME = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");

    /** File names of every migration on the class path (works from a classes directory and from the packaged jar). */
    private static List<String> migrations() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources(
                DaiPersistenceUnit.MIGRATION_LOCATION + "/*.sql");
        List<String> names = new ArrayList<>();
        for (Resource r : found) {
            names.add(String.valueOf(r.getFilename()));
        }
        return names;
    }

    @Test
    void everyMigrationFollowsTheNamingConvention() throws IOException {
        for (String file : migrations()) {
            assertThat(file).as("migration file name").matches(NAME);
        }
    }

    @Test
    void noVersionIsUsedTwice() throws IOException {
        Map<Integer, List<String>> byVersion = new HashMap<>();
        for (String file : migrations()) {
            Matcher m = NAME.matcher(file);
            if (m.matches()) {
                byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(file);
            }
        }
        byVersion.forEach((version, files) -> assertThat(files)
                .as("migration version %d is used by more than one file; renumber the newer one (Flyway refuses to start)", version)
                .hasSize(1));
    }

    @Test
    void versionsAreContiguousFromOne() throws IOException {
        List<Integer> versions = new ArrayList<>();
        for (String file : migrations()) {
            Matcher m = NAME.matcher(file);
            if (m.matches()) {
                versions.add(Integer.parseInt(m.group(1)));
            }
        }
        versions.sort(Integer::compare);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("a gap or duplicate in the migration sequence at position %d", i + 1).isEqualTo(i + 1);
        }
    }
}
