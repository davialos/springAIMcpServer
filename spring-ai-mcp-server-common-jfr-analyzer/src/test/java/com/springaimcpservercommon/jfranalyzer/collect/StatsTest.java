package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.TimePoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatsTest {

    @Test
    void percentilesUseNearestRank() {
        double[] v = Stats.sorted(new double[]{5, 1, 4, 2, 3, 6, 7, 8, 9, 10});
        assertThat(Stats.percentile(v, 50)).isEqualTo(5);
        assertThat(Stats.percentile(v, 95)).isEqualTo(10);
        assertThat(Stats.percentile(new double[0], 99)).isZero();
    }

    @Test
    void slopeOfALine() {
        assertThat(Stats.slope(new double[]{0, 1, 2, 3}, new double[]{10, 12, 14, 16})).isCloseTo(2, within(1e-9));
        assertThat(Stats.slope(new double[]{1}, new double[]{1})).isZero();
    }

    @Test
    void percentOfZeroTotalIsZero() {
        assertThat(Stats.percent(5, 0)).isZero();
        assertThat(Stats.percent(1, 4)).isEqualTo(25);
    }

    @Test
    void rateSeriesFillsEmptySecondsWithZero() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        TimeSeries s = new TimeSeries(TimeSeries.Mode.RATE);
        s.add(t0, 4);
        s.add(t0.plusMillis(500), 6);
        s.add(t0.plusSeconds(2), 3);
        List<TimePoint> points = s.points(t0, t0.plusSeconds(2));
        assertThat(points).extracting(TimePoint::value).containsExactly(10.0, 0.0, 3.0);
        assertThat(points).extracting(TimePoint::offsetMillis).containsExactly(0L, 1000L, 2000L);
    }

    @Test
    void longSeriesAreRebucketed() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        TimeSeries s = new TimeSeries(TimeSeries.Mode.AVERAGE);
        for (int i = 0; i < 10_000; i++) {
            s.add(t0.plusSeconds(i), 50);
        }
        List<TimePoint> points = s.points(t0, t0.plusSeconds(9_999));
        assertThat(points.size()).isLessThanOrEqualTo(TimeSeries.MAX_POINTS);
        assertThat(points).allMatch(p -> p.value() == 50);
    }

    @Test
    void formatsAreHumanReadable() {
        assertThat(Format.bytes(512)).isEqualTo("512 B");
        assertThat(Format.bytes(1536)).isEqualTo("1.5 KiB");
        assertThat(Format.bytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GiB");
        assertThat(Format.millis(850)).isEqualTo("850 ms");
        assertThat(Format.millis(12_300)).isEqualTo("12.3 s");
        assertThat(Format.percent(5.25)).isEqualTo("5.3%");
    }
}
