package com.springaimcpservercommon.jfranalyzer.model;

/**
 * A named bucket with its accumulated weight, e.g. a thread, an object type or a monitor class.
 *
 * @param name    display name
 * @param weight  accumulated weight in the unit of the enclosing section
 * @param count   number of events that contributed
 * @param percent share of the enclosing section's total weight (0–100)
 */
public record WeightedName(String name, double weight, long count, double percent) {
}
