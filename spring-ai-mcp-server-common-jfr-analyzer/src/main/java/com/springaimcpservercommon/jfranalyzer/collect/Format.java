package com.springaimcpservercommon.jfranalyzer.collect;

import java.util.Locale;

/** Human-readable units shared by findings and the HTML report. */
public final class Format {

    private Format() {
    }

    /**
     * @param bytes a byte count
     * @return e.g. {@code 1.5 GiB}
     */
    public static String bytes(double bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double v = Math.abs(bytes);
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, u == 0 ? "%s%.0f %s" : "%s%.1f %s", bytes < 0 ? "-" : "", v, units[u]);
    }

    /**
     * @param ms milliseconds
     * @return e.g. {@code 850 ms}, {@code 12.3 s}, {@code 4.1 min}
     */
    public static String millis(double ms) {
        if (ms < 1) {
            return String.format(Locale.ROOT, "%.2f ms", ms);
        }
        if (ms < 1_000) {
            return String.format(Locale.ROOT, "%.0f ms", ms);
        }
        if (ms < 120_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1_000);
        }
        return String.format(Locale.ROOT, "%.1f min", ms / 60_000);
    }

    /**
     * @param n a count
     * @return with thousands separators
     */
    public static String count(double n) {
        return String.format(Locale.ROOT, "%,.0f", n);
    }

    /**
     * @param p a percentage
     * @return e.g. {@code 12.3%}
     */
    public static String percent(double p) {
        return String.format(Locale.ROOT, p >= 10 || p == 0 ? "%.0f%%" : "%.1f%%", p);
    }
}
