package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.ChatUiSpec;
import com.springaimcpservercommon.ai.chat.ChatUiRuntime;
import com.springaimcpservercommon.ai.chat.ChatUiState;
import com.springaimcpservercommon.ai.chat.Choice;
import com.springaimcpservercommon.ai.runtime.ConversationKeys;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.display.AnswerContent;
import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Supporting APIs of the embeddable chat interface (LLD-13 §3, F-53), next to the chat endpoints of
 * {@link AgentChatController}:
 *
 * <ul>
 *   <li>{@code GET  /dynamic-ai/api/agents/{slug}/chat/config} — the features to offer before the first turn
 *       (steps, feedback, copy, choices), message limit and whether interactive state survives a reload</li>
 *   <li>{@code GET  …/conversations/{conversationId}/ui-state} — components shown in the conversation with their
 *       answers, and feedback per answer, to restore a reloaded chat</li>
 *   <li>{@code POST …/conversations/{conversationId}/turns/{turnId}/components/{componentId}/answer} — answers a
 *       {@code choice}: validated against the options actually shown, stored once; returns the message to send as the
 *       next chat turn</li>
 *   <li>{@code PUT|DELETE …/conversations/{conversationId}/turns/{turnId}/feedback} — like/dislike an answer, or
 *       withdraw it</li>
 * </ul>
 *
 * <p>Every call needs {@code agent:invoke} on the agent, like the chat itself. State is addressed through the hash of
 * (workspace, agent, caller, conversation), so a caller can only see and change their own conversations, whatever
 * ids they send. Not a {@code @Component}: registered by the auto-configuration.
 */
@NullMarked
@RequestMapping("/dynamic-ai/api/agents/{slug}")
public class ChatUiController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatUiController.class);
    private static final String PROBLEM = "application/problem+json;charset=UTF-8";
    private static final Pattern COMPONENT_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    /** Longest feedback comment. */
    public static final int MAX_COMMENT = 2_000;
    /** Most selected values in one answer. */
    public static final int MAX_VALUES = Choice.MAX_OPTIONS;

    /**
     * Body of an answer.
     *
     * @param values selected option values
     * @param other  typed answer, when the choice allows one
     */
    public record AnswerRequest(@Nullable List<String> values, @Nullable String other) {
    }

    /**
     * Body of a feedback call.
     *
     * @param rating  {@code up} or {@code down}; {@code null} withdraws earlier feedback
     * @param reason  optional reason code ({@code inaccurate}, {@code incomplete}, {@code off_topic}, …)
     * @param comment optional free text, at most {@value #MAX_COMMENT} characters (stored PII-redacted)
     */
    public record FeedbackRequest(@Nullable String rating, @Nullable String reason, @Nullable String comment) {
    }

    private final AgentChatController.AgentResolver agentResolver;
    private final GenericDynamicHandler.DaiPrincipalResolver principalResolver;
    private final AuthorizationEngine authorizationEngine;
    private final ChatUiRuntime chatUi;
    private final PiiRedactor redactor;
    private final AgentChatController.Settings settings;

    /**
     * Creates the controller.
     *
     * @param agentResolver       looks up the published agent by slug
     * @param principalResolver   maps the HTTP request to a {@link DaiPrincipal}
     * @param authorizationEngine authorizes {@code agent:invoke}
     * @param chatUi              host defaults and the state store
     * @param redactor            PII redactor for typed answers and comments
     * @param settings            chat limits (reported by the config endpoint)
     */
    public ChatUiController(AgentChatController.AgentResolver agentResolver,
                            GenericDynamicHandler.DaiPrincipalResolver principalResolver,
                            AuthorizationEngine authorizationEngine, ChatUiRuntime chatUi, PiiRedactor redactor,
                            AgentChatController.Settings settings) {
        this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.authorizationEngine = Objects.requireNonNull(authorizationEngine, "authorizationEngine");
        this.chatUi = Objects.requireNonNull(chatUi, "chatUi");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The resolved agent and caller, or the problem response to send. */
    private record Caller(@Nullable AgentDefinition agent, @Nullable DaiPrincipal principal,
                          @Nullable ResponseEntity<String> problem) {
    }

    private Caller authorize(String slug, HttpServletRequest http) {
        AgentDefinition agent = agentResolver.resolve(slug);
        if (agent == null) {
            return new Caller(null, null, problem(ProblemCode.NOT_FOUND, "Agent not found", null, http));
        }
        DaiPrincipal principal;
        try {
            principal = principalResolver.resolve(http);
        } catch (RuntimeException e) {
            return new Caller(null, null, problem(ProblemCode.UNAUTHENTICATED, "Authentication required", null, http));
        }
        var resource = ResourceRef.of(agent.workspaceId(), agent.id(), Classification.PUBLIC);
        if (authorizationEngine.decide(AuthorizationRequest.onResource(principal, Permission.AGENT_INVOKE, resource))
                instanceof AuthorizationOutcome.Deny) {
            return new Caller(null, null, problem(ProblemCode.ACCESS_DENIED, "Access denied", null, http));
        }
        return new Caller(agent, principal, null);
    }

    private static String conversationKey(AgentDefinition agent, DaiPrincipal principal, UUID conversationId) {
        return Sha256.of(ConversationKeys.memoryKey(agent.workspaceId(), agent.id(), principal.principalId(),
                conversationId));
    }

    // ─── Config ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Chat features of the agent, so the client can configure itself before the first turn.
     *
     * @param slug agent slug
     * @param http the request
     * @return {@code 200} {@code {agent, displayName, ui:{steps,feedback,copy,choices}, maxMessageChars,
     *         persistentState, protocol}}
     */
    @GetMapping(value = "/chat/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> config(@PathVariable String slug, HttpServletRequest http) {
        Caller c = authorize(slug, http);
        if (c.problem() != null) {
            return c.problem();
        }
        AgentDefinition agent = Objects.requireNonNull(c.agent());
        ChatUiSpec ui = chatUi.effective(agent);
        int limit = settings.maxMessageChars();
        if (agent.guardrails().maxInputChars() > 0) {
            limit = Math.min(limit, agent.guardrails().maxInputChars());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent.slug());
        m.put("displayName", agent.displayName());
        m.put("protocol", com.springaimcpservercommon.ai.runtime.StreamEvent.TurnStart.PROTOCOL);
        m.put("ui", ui == null ? flags(ChatUiSpec.OFF) : flags(ui));
        m.put("maxMessageChars", limit);
        m.put("persistentState", chatUi.state().persistent());
        return json(m);
    }

    private static Map<String, Object> flags(ChatUiSpec ui) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("steps", ui.steps());
        f.put("feedback", ui.feedback());
        f.put("copy", ui.copy());
        f.put("choices", ui.choices());
        return f;
    }

    // ─── UI state ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Components and feedback of one of the caller's conversations.
     *
     * @param slug           agent slug
     * @param conversationId the conversation
     * @param http           the request
     * @return {@code 200} {@code {persistent, components:[…], feedback:[…]}}
     */
    @GetMapping(value = "/conversations/{conversationId}/ui-state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uiState(@PathVariable String slug, @PathVariable UUID conversationId,
                                          HttpServletRequest http) {
        Caller c = authorize(slug, http);
        if (c.problem() != null) {
            return c.problem();
        }
        ChatUiState.Snapshot snapshot = chatUi.state().load(
                conversationKey(Objects.requireNonNull(c.agent()), Objects.requireNonNull(c.principal()),
                        conversationId));
        List<Object> components = new ArrayList<>();
        for (ChatUiState.StoredComponent sc : snapshot.components()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnId", sc.turnId().toString());
            m.put("componentId", sc.componentId());
            m.put("componentType", sc.componentType());
            m.put("payload", AnswerContent.parseJson(sc.payloadJson()));
            m.put("answer", sc.answerJson() == null ? null : AnswerContent.parseJson(sc.answerJson()));
            m.put("shownAt", sc.shownAt().toString());
            m.put("answeredAt", sc.answeredAt() == null ? null : sc.answeredAt().toString());
            components.add(m);
        }
        List<Object> feedback = new ArrayList<>();
        for (ChatUiState.StoredFeedback f : snapshot.feedback()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnId", f.turnId().toString());
            m.put("rating", f.rating().name().toLowerCase(Locale.ROOT));
            m.put("reason", f.reason());
            m.put("updatedAt", f.updatedAt().toString());
            feedback.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("persistent", chatUi.state().persistent());
        body.put("components", components);
        body.put("feedback", feedback);
        return json(body);
    }

    // ─── Answers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Answers a {@code choice} component. The answer must fit the options that were shown; it is stored once (a second
     * answer, also from another tab, is {@code 409}). The client then sends {@code message} as the next chat turn.
     *
     * @param slug           agent slug
     * @param conversationId conversation of the component
     * @param turnId         turn that showed it
     * @param componentId    component id within the turn
     * @param body           selected values and/or typed answer
     * @param http           the request
     * @return {@code 200} {@code {turnId, componentId, answer:{values,labels,other?}, message}}; {@code 400}
     *         invalid answer; {@code 404} unknown component; {@code 409} already answered
     */
    @PostMapping(value = "/conversations/{conversationId}/turns/{turnId}/components/{componentId}/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> answer(@PathVariable String slug, @PathVariable UUID conversationId,
                                         @PathVariable UUID turnId, @PathVariable String componentId,
                                         @RequestBody AnswerRequest body, HttpServletRequest http) {
        Caller c = authorize(slug, http);
        if (c.problem() != null) {
            return c.problem();
        }
        if (!COMPONENT_ID.matcher(componentId).matches()) {
            return problem(ProblemCode.INVALID_ARGUMENT, "Invalid component id", null, http);
        }
        List<String> values = body.values() == null ? List.of() : body.values();
        if (values.size() > MAX_VALUES || values.stream().anyMatch(v -> v == null || v.length() > Choice.MAX_LABEL)) {
            return problem(ProblemCode.INVALID_ARGUMENT, "Invalid answer", "Too many or too long values.", http);
        }
        String key = conversationKey(Objects.requireNonNull(c.agent()), Objects.requireNonNull(c.principal()),
                conversationId);
        ChatUiState state = chatUi.state();
        var stored = state.find(key, turnId, componentId);
        if (stored.isEmpty()) {
            if (!state.persistent()) {
                return problem(ProblemCode.CONFLICT, "Answers are not kept",
                        "This installation keeps no chat state; send the answer as a chat message.", http);
            }
            return problem(ProblemCode.NOT_FOUND, "Unknown component", null, http);
        }
        if (stored.get().answerJson() != null) {
            return problem(ProblemCode.CONFLICT, "Already answered", null, http);
        }
        if (!Choice.TYPE.equals(stored.get().componentType())) {
            return problem(ProblemCode.INVALID_ARGUMENT, "This component cannot be answered", null, http);
        }
        Choice choice;
        Choice.Answer answer;
        try {
            choice = Choice.fromPayloadJson(stored.get().payloadJson());
            answer = choice.validate(values, body.other(), redactor);
        } catch (Choice.InvalidChoiceException e) {
            return problem(ProblemCode.INVALID_ARGUMENT, "Invalid answer", e.getMessage(), http);
        }
        if (!state.answer(key, turnId, componentId, answer.toJson())) {
            return problem(ProblemCode.CONFLICT, "Already answered", null, http);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnId", turnId.toString());
        m.put("componentId", componentId);
        m.put("answer", AnswerContent.parseJson(answer.toJson()));
        m.put("message", answer.asMessage(choice.question()));
        return json(m);
    }

    // ─── Feedback ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Likes or dislikes an answer (replacing earlier feedback); a {@code null} rating withdraws it.
     *
     * @param slug           agent slug
     * @param conversationId conversation of the answer
     * @param turnId         the answer's turn
     * @param body           rating, reason, comment
     * @param http           the request
     * @return {@code 204}; {@code 400} invalid feedback
     */
    @PutMapping(value = "/conversations/{conversationId}/turns/{turnId}/feedback",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> putFeedback(@PathVariable String slug, @PathVariable UUID conversationId,
                                              @PathVariable UUID turnId, @RequestBody FeedbackRequest body,
                                              HttpServletRequest http) {
        Caller c = authorize(slug, http);
        if (c.problem() != null) {
            return c.problem();
        }
        AgentDefinition agent = Objects.requireNonNull(c.agent());
        DaiPrincipal principal = Objects.requireNonNull(c.principal());
        ChatUiState.Rating rating;
        if (body.rating() == null) {
            rating = null;
        } else {
            try {
                rating = ChatUiState.Rating.valueOf(body.rating().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return problem(ProblemCode.INVALID_ARGUMENT, "Invalid rating", "rating must be up or down", http);
            }
        }
        String reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().strip();
        if (reason != null && !REASON.matcher(reason).matches()) {
            return problem(ProblemCode.INVALID_ARGUMENT, "Invalid reason",
                    "reason must be a short code such as inaccurate or incomplete", http);
        }
        String comment = body.comment() == null || body.comment().isBlank() ? null : body.comment().strip();
        if (comment != null && comment.length() > MAX_COMMENT) {
            return problem(ProblemCode.INVALID_ARGUMENT, "Comment too long",
                    "comment may have at most " + MAX_COMMENT + " characters", http);
        }
        if (comment != null) {
            comment = redactor.redact(comment).text();
        }
        chatUi.state().feedback(new ChatUiState.Feedback(conversationKey(agent, principal, conversationId),
                agent.workspaceId(), agent.id(), principal.principalId(), turnId, rating,
                rating == null ? null : reason, rating == null ? null : comment));
        LOG.debug("Feedback {} for agent {} turn {}", rating, agent.slug(), turnId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Withdraws feedback on an answer.
     *
     * @param slug           agent slug
     * @param conversationId conversation of the answer
     * @param turnId         the answer's turn
     * @param http           the request
     * @return {@code 204}
     */
    @DeleteMapping("/conversations/{conversationId}/turns/{turnId}/feedback")
    public ResponseEntity<String> deleteFeedback(@PathVariable String slug, @PathVariable UUID conversationId,
                                                 @PathVariable UUID turnId, HttpServletRequest http) {
        return putFeedback(slug, conversationId, turnId, new FeedbackRequest(null, null, null), http);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────────────────────────

    private static ResponseEntity<String> json(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(CanonicalJson.write(body));
    }

    private static ResponseEntity<String> problem(ProblemCode code, String title, @Nullable String detail,
                                                  HttpServletRequest http) {
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.parseMediaType(PROBLEM))
                .body(ProblemDetailFactory.build(code, title, detail, http.getRequestURI()));
    }
}
