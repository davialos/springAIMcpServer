package com.springaimcpservercommon.ai.chat;

import com.springaimcpservercommon.core.guard.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Built-in tool {@value #NAME} (LLD-13 §3): lets the model ask the user to pick from options instead of guessing.
 * The tool validates the options, hands the {@link Choice} to the turn (which streams it as a {@code choice}
 * component and records it in {@link ChatUiState}) and tells the model to stop and wait; the user's selection
 * arrives as the next chat message.
 *
 * <p>Only offered when the agent's chat UI enables {@code choices}. One instance per turn; thread-safe.
 */
public final class PresentChoicesTool implements ToolCallback {

    private static final Logger LOG = LoggerFactory.getLogger(PresentChoicesTool.class);

    /** Tool name the model sees. */
    public static final String NAME = "present_choices";

    /** Most choices one turn may show. */
    public static final int MAX_PER_TURN = 3;

    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Ask the user to pick from a short list of options when their request is ambiguous or "
                    + "needs a decision (which record, which period, which action). The options are shown as "
                    + "buttons. After calling this tool, briefly say what you are asking and end your answer: the "
                    + "user's selection arrives as their next message. Do not use it for yes/no confirmation of "
                    + "data changes.")
            .inputSchema("""
                    {"type":"object","additionalProperties":false,"required":["question","options"],
                     "properties":{
                      "question":{"type":"string","maxLength":500,"description":"The question to show"},
                      "options":{"type":"array","minItems":2,"maxItems":12,"items":{"type":"object",
                        "additionalProperties":false,"required":["label"],"properties":{
                         "value":{"type":"string","description":"Value returned when picked; defaults to the label"},
                         "label":{"type":"string","maxLength":200},
                         "description":{"type":"string","maxLength":300}}}},
                      "multiple":{"type":"boolean","description":"Allow selecting several options"},
                      "allowOther":{"type":"boolean","description":"Allow a typed answer instead"}}}""")
            .build();

    private final PiiRedactor redactor;
    private final Consumer<Choice> onChoice;
    private final AtomicInteger count = new AtomicInteger();

    /**
     * Creates the tool for one turn.
     *
     * @param redactor PII redactor applied to every text of the choice
     * @param onChoice receives each valid choice (streams and records it)
     */
    public PresentChoicesTool(PiiRedactor redactor, Consumer<Choice> onChoice) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.onChoice = Objects.requireNonNull(onChoice, "onChoice");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String toolInput) {
        int n = count.incrementAndGet();
        if (n > MAX_PER_TURN) {
            return "{\"status\":\"error\",\"message\":\"Too many questions in one answer. Ask one question, then wait.\"}";
        }
        Choice choice;
        try {
            choice = Choice.fromToolArguments("choice-" + n, toolInput, redactor);
        } catch (Choice.InvalidChoiceException e) {
            return "{\"status\":\"error\",\"message\":" + com.springaimcpservercommon.core.json.CanonicalJson.write(
                    e.getMessage()) + "}";
        }
        try {
            onChoice.accept(choice);
        } catch (RuntimeException e) {
            LOG.warn("Showing choice {} failed; the model is told it was not shown", choice.componentId(), e);
            return "{\"status\":\"error\",\"message\":\"The options could not be shown. Ask in plain text instead.\"}";
        }
        return "{\"status\":\"shown\",\"componentId\":\"" + choice.componentId() + "\",\"message\":\"The options "
                + "are shown to the user. End your answer now; their selection arrives as the next message.\"}";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
