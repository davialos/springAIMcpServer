package com.springaimcpservercommon.ecosystem.ruleengine;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant that answers when no language model is configured. It is <b>not an LLM</b>: it recognises a handful of
 * questions by keywords, asks the library's tool path for the data (so authorization, tenant scope, audit and the tool
 * limits are exactly those of a real model) and phrases the result from templates. That keeps the local stack, its tests
 * and the whole chat pipeline (streaming, tool calls, conversation log) usable offline and deterministic.
 *
 * <p>With {@code ANTHROPIC_API_KEY} the same agent is served by a real model instead (see {@link AssistantProperties}).
 */
final class OfflineChatModel implements ChatModel {

    /** The provider id agents use to select this model (the bean is named {@code offlineChatModel}). */
    static final String PROVIDER = "offline";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern BACKTICKS = Pattern.compile("`([^`]+)`");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern AFTER_COLON = Pattern.compile(":\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("\\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|[A-Z]{4,})\\b");
    private static final Pattern STATUS = Pattern.compile("\\b(active|draft|retired)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOUT = Pattern.compile(
            "\\b(?:about|containing|contains|mentioning|named|matching|search(?:ing)? for)\\s+([A-Za-z][\\w.-]{2,})", Pattern.CASE_INSENSITIVE);
    private static final String FOOTER = "\n\n_Offline assistant: no language model is configured, so I answer from "
            + "templates. Set ANTHROPIC_API_KEY to talk to a real model._";
    private static final String HELP = """
            I can look things up in your rule setup. Try:
            - "list the active rules" or "which rules mention credit?"
            - "explain rule ADULT"
            - "list the rule groups" or "show group LOAN_ELIGIBILITY"
            - "which parameters are in the library?"
            - "check `customer.age >= 18`" (I validate CEL expressions without saving them)""" + FOOTER;

    record Intent(String tool, Map<String, Object> args) {
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Message last = prompt.getInstructions().getLast();
        if (last instanceof ToolResponseMessage tr) {
            return text(compose(tr));
        }
        Intent intent = route(last.getText() == null ? "" : last.getText());
        return intent == null ? text(HELP) : toolCall(intent);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Message last = prompt.getInstructions().getLast();
        if (last instanceof ToolResponseMessage tr) {
            // the final answer, streamed line by line the way a model streams tokens
            List<String> lines = new ArrayList<>();
            for (String line : compose(tr).split("(?<=\n)")) {
                lines.add(line);
            }
            return Flux.fromIterable(lines).map(OfflineChatModel::text);
        }
        return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model("offline-rule-assistant").build();
    }

    // ── from the user's words to a tool ─────────────────────────────────────────────────────────────────

    static @Nullable Intent route(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        Matcher code = CODE.matcher(message);
        String codeToken = code.find() ? code.group(1) : null;

        if (m.contains("check") || m.contains("validate") || m.contains("compile") || m.contains("valid?")) {
            String expression = expression(message);
            if (expression != null) {
                return new Intent("check_cel_expression", Map.of("expression", expression));
            }
        }
        if (m.matches("(?s).*\\b(parameter|parameters|library|variable|variables|attribute|attributes)\\b.*")) {
            return new Intent("list_library_parameters", new LinkedHashMap<>());
        }
        if (m.matches("(?s).*\\bgroups?\\b.*")) {
            if (codeToken != null) {
                return new Intent("get_rule_group", Map.of("code", codeToken));
            }
            Map<String, Object> args = new LinkedHashMap<>();
            String about = about(message);
            if (about != null) {
                args.put("search", about);
            }
            return new Intent("list_rule_groups", args);
        }
        if (m.matches("(?s).*\\b(rule|rules|expression|expressions)\\b.*") || codeToken != null) {
            if (codeToken != null && (m.contains("explain") || m.contains("show") || m.contains("what") || m.contains("describe")
                    || m.contains("rule " + codeToken.toLowerCase(java.util.Locale.ROOT)))) {
                return new Intent("get_rule", Map.of("code", codeToken));
            }
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher status = STATUS.matcher(message);
            if (status.find()) {
                args.put("status", status.group(1).toUpperCase(java.util.Locale.ROOT));
            }
            String about = about(message);
            if (about != null) {
                args.put("search", about);
            }
            return new Intent("list_rules", args);
        }
        return null;
    }

    private static @Nullable String expression(String message) {
        for (Pattern p : List.of(BACKTICKS, DOUBLE_QUOTED, AFTER_COLON)) {
            Matcher x = p.matcher(message);
            if (x.find() && !x.group(1).isBlank()) {
                return x.group(1).trim();
            }
        }
        return null;
    }

    private static @Nullable String about(String message) {
        Matcher a = ABOUT.matcher(message);
        return a.find() ? a.group(1) : null;
    }

    private static ChatResponse toolCall(Intent intent) {
        String args;
        try {
            args = JSON.writeValueAsString(intent.args());
        } catch (RuntimeException e) {
            args = "{}";
        }
        var call = new AssistantMessage.ToolCall("call-" + UUID.randomUUID(), "function", intent.tool(), args);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(call)).build())));
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ── from a tool result to words ─────────────────────────────────────────────────────────────────────

    private static String compose(ToolResponseMessage tr) {
        StringBuilder out = new StringBuilder();
        for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(describe(r.name(), r.responseData()));
        }
        return out.append(FOOTER).toString();
    }

    static String describe(String tool, String envelopeJson) {
        JsonNode env;
        try {
            env = JSON.readTree(envelopeJson);
        } catch (RuntimeException e) {
            return "I could not read the result of " + tool + ".";
        }
        String status = env.path("status").asString("ok");
        if ("not_permitted".equals(status)) {
            return "You are not allowed to use that.";
        }
        if ("error".equals(status)) {
            return "I could not look that up (" + env.path("error").path("code").asString("error") + ").";
        }
        JsonNode first = env.path("data").isArray() && !env.path("data").isEmpty() ? env.path("data").get(0) : null;
        if ("check_cel_expression".equals(tool)) {
            return first == null ? "I got no answer from the check." : checkAnswer(first);
        }
        JsonNode items = first != null && first.has("items") ? first.get("items") : env.path("data");
        boolean truncated = first != null && first.path("truncated").asBoolean(false);
        int total = first != null ? first.path("total").asInt(items.size()) : items.size();
        return switch (tool) {
            case "list_rules" -> rules(items, total, truncated, false);
            case "get_rule" -> rules(items, total, false, true);
            case "list_rule_groups" -> groups(items, total, truncated, false);
            case "get_rule_group" -> groups(items, total, false, true);
            case "list_library_parameters" -> library(items);
            default -> "Done (" + tool + ").";
        };
    }

    private static String checkAnswer(JsonNode r) {
        if (r.path("valid").asBoolean(false)) {
            List<String> p = strings(r.path("parameters"));
            return "That expression is valid" + (p.isEmpty() ? "." : " and reads: " + String.join(", ", p) + ".");
        }
        return "That expression is not valid: " + r.path("error").asString("unknown error");
    }

    private static String rules(JsonNode items, int total, boolean truncated, boolean detail) {
        if (items.isEmpty()) {
            return "I found no matching rule that you can see.";
        }
        StringBuilder b = new StringBuilder();
        if (!detail) {
            b.append("I found ").append(total).append(total == 1 ? " rule" : " rules").append(truncated ? " (showing the first "
                    + items.size() + ")" : "").append(":\n");
        }
        for (JsonNode r : items) {
            b.append("- **").append(r.path("code").asString()).append("** — ").append(r.path("name").asString())
                    .append(" [").append(r.path("status").asString()).append(", ").append(r.path("scope").asString().toLowerCase(java.util.Locale.ROOT))
                    .append("]\n  `").append(r.path("expression").asString()).append("`\n");
            if (detail) {
                b.append("  When true → ").append(r.path("trueAction").asString()).append("; when false → ")
                        .append(r.path("falseAction").asString()).append(".\n");
                List<String> p = strings(r.path("parameters"));
                if (!p.isEmpty()) {
                    b.append("  Reads: ").append(String.join(", ", p)).append(".\n");
                }
                List<String> g = strings(r.path("groups"));
                if (!g.isEmpty()) {
                    b.append("  Used in groups: ").append(String.join(", ", g)).append(".\n");
                }
            }
        }
        return b.toString().stripTrailing();
    }

    private static String groups(JsonNode items, int total, boolean truncated, boolean detail) {
        if (items.isEmpty()) {
            return "I found no matching rule group that you can see.";
        }
        StringBuilder b = new StringBuilder();
        if (!detail) {
            b.append("I found ").append(total).append(total == 1 ? " rule group" : " rule groups")
                    .append(truncated ? " (showing the first " + items.size() + ")" : "").append(":\n");
        }
        for (JsonNode g : items) {
            b.append("- **").append(g.path("code").asString()).append("** — ").append(g.path("name").asString())
                    .append(" [").append(g.path("status").asString()).append(", ").append(g.path("policy").asString())
                    .append(" on ").append(g.path("matchOn").asString()).append("]\n");
            List<String> rules = strings(g.path("rules"));
            if (!rules.isEmpty()) {
                b.append("  Rules in order: ").append(String.join(" → ", rules)).append(".\n");
            }
            List<String> triggers = strings(g.path("triggers"));
            if (!triggers.isEmpty()) {
                b.append("  Triggered by: ").append(String.join("; ", triggers)).append(".\n");
            }
            if (detail) {
                b.append("  When a rule cannot be evaluated: ").append(g.path("onError").asString()).append(".\n");
            }
        }
        return b.toString().stripTrailing();
    }

    private static String library(JsonNode items) {
        if (items.isEmpty()) {
            return "The parameter library has nothing matching.";
        }
        StringBuilder b = new StringBuilder("The parameter library (the only variables a rule may read):\n");
        for (JsonNode o : items) {
            b.append("- **").append(o.path("object").asString()).append("** (").append(o.path("name").asString()).append("): ")
                    .append(String.join(", ", strings(o.path("parameters")))).append("\n");
        }
        return b.toString().stripTrailing();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
