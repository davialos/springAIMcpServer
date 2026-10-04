package com.springaimcpservercommon.ai.agent;

/**
 * Which chat-interface features a turn offers the client (LLD-13 §3, sent in {@code turn.start}). Configured per agent
 * ({@code output.ui} in the agent spec) or, when the agent says nothing, by the host defaults
 * ({@code dynamic.ai.agent.chat.ui.*}).
 *
 * @param steps    stream step details: request checks and tool calls ({@code step}, {@code tool.call},
 *                 {@code tool.result} events)
 * @param feedback the client offers like/dislike on answers ({@code POST …/turns/{turnId}/feedback})
 * @param copy     the client offers copy buttons on answers, step details and copyable components
 * @param choices  the model may ask the user to pick from options (built-in {@code present_choices} tool, rendered
 *                 as a {@code choice} component and answered through the next chat request)
 */
public record ChatUiSpec(boolean steps, boolean feedback, boolean copy, boolean choices) {

    /** Steps, feedback and copy on; choices off (the model gets no extra tool unless an agent asks for it). */
    public static final ChatUiSpec DEFAULT = new ChatUiSpec(true, true, true, false);

    /** Everything off: a plain text stream. */
    public static final ChatUiSpec OFF = new ChatUiSpec(false, false, false, false);
}
