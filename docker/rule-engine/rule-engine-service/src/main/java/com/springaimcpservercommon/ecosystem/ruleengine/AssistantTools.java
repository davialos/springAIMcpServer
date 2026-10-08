package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ExpressionCheck;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.LibraryObject;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleView;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * What the AI assistant may do with the rule setup: look things up and check CEL. These are the plain operations; the
 * annotated, model-facing wrappers are {@code com.example.ruleconsole.assistant.RuleSetupTools}, in a package outside the
 * library's own (the library's catalog scan skips its own package). Nothing here writes (ADR-0009: a model
 * never changes data) and nothing evaluates a rule group, which would leave evaluation rows behind.
 *
 * <p>The agent runtime calls these methods through the Spring proxy <em>as the signed-in user</em>, so {@link
 * Caller#current()} is the user's tenant and organization taken from their token: the same visibility as the console,
 * enforced by the same repository code. The model cannot name another tenant, it has no parameter for it.
 *
 * <p>Results are bounded ({@value #MAX_ITEMS} items, expressions cut at {@value #MAX_EXPRESSION} characters) so a large
 * setup cannot flood the model's context; the answer says when it was cut.
 */
@Service
public class AssistantTools {

    static final int MAX_ITEMS = 25;
    static final int MAX_EXPRESSION = 400;

    private final CatalogRepository catalog;
    private final AuthoringService authoring;

    AssistantTools(CatalogRepository catalog, AuthoringService authoring) {
        this.catalog = catalog;
        this.authoring = authoring;
    }

    /** A rule as the model sees it. */
    public record RuleBrief(String code, String name, String module, String status, String scope, String expression,
                            String trueAction, String falseAction, List<String> parameters, List<String> groups) {
    }

    /** A rule group as the model sees it. */
    public record GroupBrief(String code, String name, String module, String status, String scope, String policy,
                             String matchOn, String onError, List<String> rules, List<String> triggers) {
    }

    /** A library object with its parameters. */
    public record ParameterBrief(String object, String name, List<String> parameters) {
    }

    /** One page of results and whether there were more. */
    public record Listing<T>(int total, boolean truncated, List<T> items) {
    }

    /** Rules the caller can see, optionally by status or a text in code, name, description or expression. */
    public Listing<RuleBrief> listRules(@Nullable String status, @Nullable String search) {
        Caller c = Caller.current();
        String st = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String q = blank(search) ? null : search.trim().toLowerCase(Locale.ROOT);
        List<RuleView> all = catalog.rules(c, null, st).stream()
                .filter(r -> q == null || contains(q, r.code(), r.name(), r.description(), r.expression())).toList();
        return new Listing<>(all.size(), all.size() > MAX_ITEMS, all.stream().limit(MAX_ITEMS).map(AssistantTools::brief).toList());
    }

    /** One rule by code (case-insensitive), with its full expression. */
    public Listing<RuleBrief> getRule(String code) {
        Caller c = Caller.current();
        List<RuleBrief> found = catalog.rules(c, null, null).stream().filter(r -> r.code().equalsIgnoreCase(code))
                .map(r -> new RuleBrief(r.code(), r.name(), r.moduleCode(), r.status(), r.scope(), r.expression(),
                        r.trueAction(), r.falseAction(), r.parameters(), r.groups())).toList();
        return new Listing<>(found.size(), false, found);
    }

    /** Rule groups the caller can see, optionally by a text in code, name or member rule codes. */
    public Listing<GroupBrief> listRuleGroups(@Nullable String search) {
        Caller c = Caller.current();
        String q = blank(search) ? null : search.trim().toLowerCase(Locale.ROOT);
        List<GroupView> all = catalog.groups(c, null, null).stream().filter(g -> q == null || contains(q, g.code(),
                g.name(), g.description(), String.join(" ", g.rules().stream().map(Dtos.GroupRuleView::ruleCode).toList()))).toList();
        return new Listing<>(all.size(), all.size() > MAX_ITEMS, all.stream().limit(MAX_ITEMS).map(AssistantTools::brief).toList());
    }

    /** One rule group by code (case-insensitive). */
    public Listing<GroupBrief> getRuleGroup(String code) {
        Caller c = Caller.current();
        List<GroupBrief> found = catalog.groups(c, null, null).stream().filter(g -> g.code().equalsIgnoreCase(code))
                .map(AssistantTools::brief).toList();
        return new Listing<>(found.size(), false, found);
    }

    /** The parameter library: objects with their {@code object.attribute : TYPE} variables. */
    public Listing<ParameterBrief> listLibraryParameters(@Nullable String object) {
        Caller c = Caller.current();
        List<LibraryObject> objects = catalog.library(c).stream()
                .filter(o -> blank(object) || o.code().equalsIgnoreCase(object.trim())).toList();
        List<ParameterBrief> items = objects.stream().map(o -> new ParameterBrief(o.code(), o.name(),
                o.attributes().stream().map(a -> a.celName() + " : " + a.dataType()).toList())).toList();
        return new Listing<>(items.size(), false, items);
    }

    /** Compiles an expression against the library without saving anything. */
    public ExpressionCheck checkCelExpression(String expression) {
        return authoring.check(Caller.current(), expression);
    }

    private static RuleBrief brief(RuleView r) {
        String e = r.expression() == null ? "" : r.expression();
        return new RuleBrief(r.code(), r.name(), r.moduleCode(), r.status(), r.scope(),
                e.length() > MAX_EXPRESSION ? e.substring(0, MAX_EXPRESSION) + "…" : e, r.trueAction(), r.falseAction(),
                r.parameters(), r.groups());
    }

    private static GroupBrief brief(GroupView g) {
        return new GroupBrief(g.code(), g.name(), g.moduleCode(), g.status(), g.scope(), g.policy(), g.matchOn(), g.onError(),
                g.rules().stream().sorted(java.util.Comparator.comparingInt(Dtos.GroupRuleView::sequence))
                        .map(r -> r.ruleCode() + (r.enabled() ? "" : " (disabled)")).toList(),
                g.triggers().stream().map(t -> t.application() + " / " + t.formCode() + " / " + t.actionCode()
                        + (t.fieldCode() == null ? "" : " / " + t.fieldCode())).toList());
    }

    private static boolean contains(String lowerQuery, @Nullable String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
