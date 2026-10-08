package com.springaimcpservercommon.celfaker.pipeline;

import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.model.Parameter;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Builds the CEL parameter library for selected payload candidates. */
public final class FakerLibrary {

    private FakerLibrary() {
    }

    /**
     * Parameters for candidates; the attribute id is derived from the CEL name, so it is stable between runs.
     *
     * @param candidates selected candidates
     * @return parameters
     */
    public static List<Parameter> parameters(Collection<Candidate> candidates) {
        return candidates.stream()
                .map(c -> new Parameter(UUID.nameUUIDFromBytes(c.celName().getBytes(StandardCharsets.UTF_8)),
                        c.objectCode(), c.attributeCode(), c.type(), false))
                .toList();
    }

    /**
     * The CEL environment for candidates.
     *
     * @param candidates selected candidates
     * @return the library
     */
    public static ParameterLibrary library(Collection<Candidate> candidates) {
        return new ParameterLibrary(parameters(candidates));
    }
}
