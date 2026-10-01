package com.springaimcpservercommon.jfranalyzer.collect;

/**
 * One frame of a recorded stack.
 *
 * @param method    the method
 * @param line      source line, {@code <= 0} if unknown
 * @param frameType JFR frame type
 */
public record ResolvedFrame(MethodInfo method, int line, String frameType) {

    /** @return stack-trace style location */
    public String location() {
        return method.location(line, frameType);
    }
}
