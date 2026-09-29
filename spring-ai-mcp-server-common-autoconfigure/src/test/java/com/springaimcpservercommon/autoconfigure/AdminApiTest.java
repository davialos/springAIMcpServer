package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminApiTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    void pageDefaultsAndBounds() {
        assertThat(AdminApi.page(null, null)).isEqualTo(new PageRequest(0, AdminApi.DEFAULT_LIMIT));
        assertThat(AdminApi.page(200, 10)).isEqualTo(new PageRequest(10, 200));
        assertThatThrownBy(() -> AdminApi.page(201, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.page(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.page(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowDefaultsToLast24Hours() {
        TimeRange range = AdminApi.window(null, null, NOW);
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void windowHonoursExplicitBounds() {
        TimeRange range = AdminApi.window("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", NOW);
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    void windowRejectsInvertedOrHugeOrUnparsableRanges() {
        assertThatThrownBy(() -> AdminApi.window("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.window("2020-01-01T00:00:00Z", "2026-09-01T00:00:00Z", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.window("yesterday", null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be an ISO-8601 instant");
    }

    @Test
    void ifMatchAcceptsQuotedWeakAndBareVersions() {
        assertThat(AdminApi.ifMatch(null)).isNull();
        assertThat(AdminApi.ifMatch(" ")).isNull();
        assertThat(AdminApi.ifMatch("\"7\"")).isEqualTo(7L);
        assertThat(AdminApi.ifMatch("W/\"12\"")).isEqualTo(12L);
        assertThat(AdminApi.ifMatch("0")).isEqualTo(0L);
        assertThatThrownBy(() -> AdminApi.ifMatch("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.ifMatch("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textStripsAndValidates() {
        List<FieldViolation> errors = new ArrayList<>();
        assertThat(AdminApi.text(errors, "name", "  Sales  ", true, 10)).isEqualTo("Sales");
        assertThat(AdminApi.text(errors, "description", null, false, 10)).isNull();
        assertThat(errors).isEmpty();

        assertThat(AdminApi.text(errors, "name", " ", true, 10)).isNull();
        assertThat(AdminApi.text(errors, "name", "12345678901", true, 10)).isNull();
        assertThat(AdminApi.text(errors, "name", "a\u0007b", true, 10)).isNull();
        assertThat(errors).hasSize(3);
    }
}
