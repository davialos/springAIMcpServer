package com.example.ruleconsole.assistant;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import com.springaimcpservercommon.ecosystem.ruleengine.AssistantTools;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The tools of the rule assistant, as the library's catalog scan sees them: every method is read-only, and the data comes
 * from {@link AssistantTools}, which answers for the signed-in user's tenant and organization only (the runtime calls these
 * methods as that user; a model has no parameter to name another one). Nothing here saves, activates or evaluates a rule.
 */
@Service
@AiContext(description = "The caller's CEL rule setup: business rules written as CEL expressions, rule groups that "
        + "evaluate them under a policy (FIRST_MATCH, ALL_MATCH, EVALUATE_ALL, COMPOSITE), and the parameter library "
        + "(object.attribute variables) the expressions read. Read-only, limited to the caller's tenant and organization.",
        keywords = {"rule", "rule group", "CEL", "parameter", "policy", "trigger"})
public class RuleSetupTools {

    /**
     * The library renders a tool result as a plain value tree (maps, lists, strings, numbers, booleans) and refuses
     * anything else, a host's own records included: the typed results are converted here, once. Not a Spring bean (the
     * library never exposes a mapper as one, ADR-0019).
     */
    private static final JsonMapper TREE = JsonMapper.builder().build();

    private final AssistantTools tools;

    public RuleSetupTools(AssistantTools tools) {
        this.tools = tools;
    }

    @AiExposedAction(name = "list_rules", readOnly = true, idempotent = true,
            intent = "List the CEL rules the user can see, optionally only one status or rules whose code, name or "
                    + "expression contains a text.",
            keywords = {"rules", "list", "find", "search"})
    public Map<String, Object> listRules(
            @AiParam(description = "Only rules with this status: DRAFT, ACTIVE or RETIRED.", required = false,
                    examples = {"ACTIVE"}) @Nullable String status,
            @AiParam(description = "Only rules whose code, name, description or expression contains this text "
                    + "(case-insensitive).", required = false, examples = {"credit"}) @Nullable String search) {
        return tree(tools.listRules(status, search));
    }

    @AiExposedAction(name = "get_rule", readOnly = true, idempotent = true,
            intent = "Show one rule by its code: the full CEL expression, its actions, the parameters it reads and the "
                    + "rule groups it belongs to.",
            keywords = {"rule", "explain", "expression"})
    public Map<String, Object> getRule(
            @AiParam(description = "The rule code, e.g. ADULT or CREDIT_SCORE_MIN.", examples = {"ADULT"}) String code) {
        return tree(tools.getRule(code));
    }

    @AiExposedAction(name = "list_rule_groups", readOnly = true, idempotent = true,
            intent = "List the rule groups the user can see with their evaluation policy, rules in order and the form "
                    + "actions that trigger them; optionally filtered by a text in code, name or rule codes.",
            keywords = {"groups", "policy", "trigger", "list"})
    public Map<String, Object> listRuleGroups(
            @AiParam(description = "Only groups whose code, name or a member rule code contains this text.",
                    required = false, examples = {"LOAN"}) @Nullable String search) {
        return tree(tools.listRuleGroups(search));
    }

    @AiExposedAction(name = "get_rule_group", readOnly = true, idempotent = true,
            intent = "Show one rule group by its code: policy, match mode, error handling, member rules in sequence and triggers.",
            keywords = {"group", "explain", "policy"})
    public Map<String, Object> getRuleGroup(
            @AiParam(description = "The rule group code, e.g. LOAN_ELIGIBILITY.", examples = {"LOAN_ELIGIBILITY"}) String code) {
        return tree(tools.getRuleGroup(code));
    }

    @AiExposedAction(name = "list_library_parameters", readOnly = true, idempotent = true,
            intent = "List the parameter library: objects and their attributes with CEL names (object.attribute) and "
                    + "types, which are the only variables a CEL rule may read.",
            keywords = {"parameters", "library", "variables", "attributes", "types"})
    public Map<String, Object> listLibraryParameters(
            @AiParam(description = "Only this object code, e.g. customer or loan.", required = false,
                    examples = {"customer"}) @Nullable String object) {
        return tree(tools.listLibraryParameters(object));
    }

    @AiExposedAction(name = "check_cel_expression", readOnly = true, idempotent = true,
            intent = "Check whether a CEL boolean expression compiles against the parameter library, without saving "
                    + "anything. Answers valid or the compiler's message and the parameters it reads.",
            keywords = {"cel", "validate", "check", "expression", "syntax"})
    public Map<String, Object> checkCelExpression(
            @AiParam(description = "The CEL expression to check, e.g. customer.age >= 18 && customer.kycStatus == 'VERIFIED'.",
                    examples = {"customer.age >= 18"}) String expression) {
        return tree(tools.checkCelExpression(expression));
    }

    private static Map<String, Object> tree(Object result) {
        return TREE.convertValue(result, new TypeReference<Map<String, Object>>() { });
    }
}
