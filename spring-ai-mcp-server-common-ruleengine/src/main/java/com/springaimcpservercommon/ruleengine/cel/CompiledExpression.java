package com.springaimcpservercommon.ruleengine.cel;

import com.springaimcpservercommon.ruleengine.model.Parameter;
import dev.cel.runtime.CelRuntime;

import java.util.List;

/**
 * A type-checked, ready-to-run CEL program. Immutable and thread-safe.
 *
 * @param source     the CEL text it was compiled from
 * @param program    the runtime program
 * @param referenced library parameters the expression reads (for fact binding and impact analysis)
 */
public record CompiledExpression(String source, CelRuntime.Program program, List<Parameter> referenced) {

    /** Defensive copy. */
    public CompiledExpression {
        referenced = List.copyOf(referenced);
    }
}
