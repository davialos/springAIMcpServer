package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;

/**
 * Attribution of one kind of cost (CPU samples, allocated bytes, blocked time, ...) to the requested packages.
 *
 * @param title             section title
 * @param unit              unit of every weight in this section: {@code samples}, {@code bytes}, {@code nanos} or
 *                          {@code events}
 * @param detailLabel       what {@link Hotspot#details()} and {@link #topDetails()} break down by, or empty
 * @param events            number of events seen
 * @param totalWeight       total weight of all events
 * @param attributedWeight  weight of events whose stack contains a frame in a requested package
 * @param attributedPercent {@code attributedWeight / totalWeight} (0–100)
 * @param truncatedStacks   number of events whose JFR stack trace was truncated
 * @param hotspots          methods in the packages, by attributed weight
 * @param inclusive         methods in the packages, by weight of events with the method anywhere on the stack
 * @param hotLines          hottest lines across all methods in the packages
 * @param outside           leaf frames of events with no frame in the packages (where the rest went)
 * @param topDetails        section-specific breakdown over all events
 * @param topThreads        threads over all events
 */
public record HotspotReport(String title, String unit, String detailLabel, long events, double totalWeight,
                            double attributedWeight, double attributedPercent, long truncatedStacks,
                            List<Hotspot> hotspots, List<WeightedName> inclusive, List<HotLine> hotLines,
                            List<WeightedName> outside, List<WeightedName> topDetails, List<WeightedName> topThreads) {

    /** @return whether the recording contained any event for this section */
    public boolean present() {
        return events > 0;
    }
}
