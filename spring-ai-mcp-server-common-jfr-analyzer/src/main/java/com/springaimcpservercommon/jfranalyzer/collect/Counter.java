package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.WeightedName;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Weighted counts by name. */
public final class Counter {

    private final Map<String, double[]> values = new HashMap<>();

    /**
     * @param name   bucket
     * @param weight weight to add
     */
    public void add(String name, double weight) {
        double[] v = values.computeIfAbsent(name, k -> new double[2]);
        v[0] += weight;
        v[1]++;
    }

    /** @return whether nothing was added */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * @param limit maximum rows
     * @param total denominator for {@link WeightedName#percent()}
     * @return the heaviest buckets, heaviest first (ties by name)
     */
    public List<WeightedName> top(int limit, double total) {
        return values.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -e.getValue()[0])
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> new WeightedName(e.getKey(), e.getValue()[0], (long) e.getValue()[1],
                        Stats.percent(e.getValue()[0], total)))
                .toList();
    }
}
