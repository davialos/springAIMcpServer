# ADR-0027: Context-aware validation module
- Status: Accepted
- Date: 2026-10-07
- Deciders: product owner (request of 2026-10-07)

## Context
Hosts need one call, `validate(target, context)`, usable at any stage of request processing (controller, service,
pre-persist, event handler), where *which* rules run and *in what order* depends on the business state, the API
endpoint, the user action and the stage.

## Decision
- New module `spring-ai-mcp-server-common-validation` (package `…validation`, **no Spring**, only slf4j/jspecify).
  `DaiValidationAutoConfiguration` collects every `ValidationRule<?>` and `OrderOverride` bean into a
  `@ConditionalOnMissingBean Validator`. Not in the default starter; the host adds the artifact.
- `ValidationContext(stage, state, endpoint, action, attributes)`; `Scope` selects contexts per coordinate with `*`
  and `a|b` patterns (endpoint convention `"POST /orders/*"`).
- Order = rule default `order()`, replaced per context by the most specific matching `OrderOverride` (ties: last
  registered); an override can also skip a rule. Rules need no change to be re-ordered per endpoint/state/action.
- Per call `FAIL_FAST` or `COLLECT_ALL`; per rule `stopOnFailure`. A throwing rule yields a `rule_error` violation
  (fail closed; exception message never logged or returned). `Validator.explain` shows the plan without running it.
- Default deny is unaffected: validation only adds checks, it never grants access.

## Usage
```java
ValidationRule<Order> coupon = Rules.forType(Order.class).id("coupon").order(30)
    .scope(Scope.action("SUBMIT").andState("DRAFT|REJECTED"))
    .check(o -> o.coupon() != null, "coupon", "coupon required to submit");

Validator v = Validator.builder().rule(coupon)
    .override(OrderOverride.reorder("coupon", Scope.endpoint("POST /orders/bulk"), 1))
    .build();                       // or inject the auto-configured bean

v.validateOrThrow(order, ValidationContext.of("SERVICE", "DRAFT", "POST /orders", "SUBMIT"));
```

## Spring integration
- `dynamic.ai.agent.validation.overrides[n]` (rule, stage/state/endpoint/action, order or skip) adds overrides from
  configuration; `...validation.enabled=false` removes the bean.
- Opt-in `...validation.web.enabled=true`: `ValidatingRequestBodyAdvice` validates every host `@RequestBody` at stage
  `CONTROLLER` (endpoint = `METHOD pattern`, action = handler method name, replaceable via a
  `ValidationContextResolver` bean; `/dynamic-ai/**` skipped) and `ValidationProblemAdvice` answers 422 problem+json
  with rule/field/code/message only (no values). Off by default because it touches the host's MVC pipeline (LLD-12).

## Consequences
Pure-Java core is testable without Spring and callable from any layer. Overrides come from beans or configuration;
persisting them in `dynamic_ai` for runtime admin edits is a possible follow-up (not decided).
