package com.springaimcpservercommon.celfaker.workflow;

import com.springaimcpservercommon.celfaker.payload.JsonValues;

import java.util.List;

/**
 * A flow of API calls: the thing drawn on the dashboard canvas and executed by the generated k6 script.
 *
 * @param name  workflow name
 * @param steps the steps (order of the list is irrelevant; {@code dependsOn} decides)
 * @param load  load shape
 */
public record Workflow(String name, List<Step> steps, Load load) {

    /** Normalises omitted fields. */
    public Workflow {
        name = name == null || name.isBlank() ? "workflow" : name;
        steps = steps == null ? List.of() : List.copyOf(steps);
        load = load == null ? Load.smoke() : load;
    }

    /**
     * Reads a workflow file.
     *
     * @param json file content
     * @return the workflow
     */
    public static Workflow fromJson(String json) {
        return JsonValues.MAPPER.readValue(json, Workflow.class);
    }
}
