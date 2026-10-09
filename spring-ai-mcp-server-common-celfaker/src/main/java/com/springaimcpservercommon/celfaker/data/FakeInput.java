package com.springaimcpservercommon.celfaker.data;

import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.expr.CaseBuilder;
import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.celfaker.pipeline.FakerLibrary;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.ValueFactory;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fake input for one API on its own: what the dashboard shows right after an API is added. */
public final class FakeInput {

    private FakeInput() {
    }

    /**
     * Generates valid and invalid request bodies for an API.
     *
     * @param api   the API (example body, selected paths, rules)
     * @param seed  seed
     * @param count number of valid bodies
     * @return the data; empty when the API has no JSON body
     */
    public static ApiData generate(ApiSpec api, long seed, int count) {
        if (!api.hasBody()) {
            return new ApiData(api.id(), List.of(), List.of(), List.of("this API has no JSON request body to fake"));
        }
        Set<String> selected = new HashSet<>(api.selectedPaths());
        List<Candidate> candidates = PayloadAnalyzer.analyze(api.requestExample(), api.sysObject(), new HashSet<>(api.mapPaths()))
                .candidates().stream().filter(c -> selected.isEmpty() || selected.contains(c.path())).toList();
        ParameterLibrary library = FakerLibrary.library(candidates);
        AttributeValueMap values = new ValueFactory(seed).build(candidates);
        CaseBuilder cases = new CaseBuilder(library, values, seed);
        return new DataGenerator(values, cases, seed).generate(api, candidates, Math.max(1, Math.min(count, 500)));
    }
}
