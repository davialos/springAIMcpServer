package com.springaimcpservercommon.jfranalyzer.collect;

import java.util.Arrays;

/** Small numeric helpers. */
public final class Stats {

    private Stats() {
    }

    /**
     * @param part  numerator
     * @param total denominator
     * @return {@code part / total * 100}, or 0 when {@code total <= 0}
     */
    public static double percent(double part, double total) {
        return total <= 0 ? 0 : part * 100.0 / total;
    }

    /**
     * Nearest-rank percentile.
     *
     * @param sorted ascending values
     * @param p      percentile, 0–100
     * @return the value, or 0 for no values
     */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)];
    }

    /**
     * @param values values in any order
     * @return a sorted copy
     */
    public static double[] sorted(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    /**
     * Least-squares slope of y over x.
     *
     * @param x x values
     * @param y y values, same length
     * @return slope, or 0 for fewer than two distinct x
     */
    public static double slope(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            return 0;
        }
        double mx = Arrays.stream(x).average().orElse(0);
        double my = Arrays.stream(y).average().orElse(0);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (x[i] - mx) * (y[i] - my);
            den += (x[i] - mx) * (x[i] - mx);
        }
        return den == 0 ? 0 : num / den;
    }

    /**
     * @param value a number
     * @param digits decimal places to keep
     * @return the number rounded half-up, keeping reports readable
     */
    public static double round(double value, int digits) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        double f = Math.pow(10, digits);
        return Math.round(value * f) / f;
    }
}
