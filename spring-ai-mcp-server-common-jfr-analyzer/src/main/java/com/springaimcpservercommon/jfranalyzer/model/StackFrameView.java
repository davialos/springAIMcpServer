package com.springaimcpservercommon.jfranalyzer.model;

/**
 * One frame of a representative stack, leaf first.
 *
 * @param location  stack-trace style location
 * @param inPackage whether the frame belongs to one of the requested packages
 * @param frameType JFR frame type ({@code Interpreted}, {@code JIT compiled}, {@code Inlined}, {@code Native})
 */
public record StackFrameView(String location, boolean inPackage, String frameType) {
}
