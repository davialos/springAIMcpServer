package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;

/**
 * A method in a requested package that events are attributed to: the frame closest to the leaf of the stack that
 * lies in one of the packages. This is the place in <em>your</em> code that caused the cost, even when the cost was
 * paid inside the JDK or a library it called (see {@link #callees()}).
 *
 * @param rank         1-based rank in its section
 * @param method       method display name, {@code pkg.Class.method(ParamType, ...)}
 * @param className    fully qualified class name
 * @param methodName   method name
 * @param location     stack-trace style location of the hottest line, clickable in IDE consoles
 * @param weight       attributed weight
 * @param count        number of events
 * @param percent      share of the section total (0–100)
 * @param selfWeight   part of {@code weight} where this method was itself the leaf frame
 * @param lines        hottest lines in this method
 * @param callees      where the cost was actually paid: the leaf frame ({@code (self)} when it is this method)
 * @param details      section-specific breakdown, e.g. allocated type or monitor class
 * @param threads      threads the events came from
 * @param stacks       most common call paths through this method
 */
public record Hotspot(int rank, String method, String className, String methodName, String location,
                      double weight, long count, double percent, double selfWeight,
                      List<HotLine> lines, List<WeightedName> callees, List<WeightedName> details,
                      List<WeightedName> threads, List<StackPath> stacks) {
}
