package com.springaimcpservercommon.validation;

/**
 * Selects the contexts a rule or an ordering override applies to. Each coordinate is a pattern where {@code *}
 * matches anything and {@code |} separates alternatives (e.g. {@code "DRAFT|REJECTED"}, {@code "POST /orders/*"}).
 * Coordinates left as {@code *} are open.
 */
public final class Scope {

    /** Matches every context. */
    public static final Scope ANY = new Scope("*", "*", "*", "*");

    private final String stage;
    private final String state;
    private final String endpoint;
    private final String action;
    private final Glob stageGlob;
    private final Glob stateGlob;
    private final Glob endpointGlob;
    private final Glob actionGlob;

    private Scope(String stage, String state, String endpoint, String action) {
        this.stage = stage;
        this.state = state;
        this.endpoint = endpoint;
        this.action = action;
        this.stageGlob = new Glob(stage);
        this.stateGlob = new Glob(state);
        this.endpointGlob = new Glob(endpoint);
        this.actionGlob = new Glob(action);
    }

    /**
     * Creates a scope; {@code null} or blank means open ({@code *}).
     *
     * @param stage    stage pattern
     * @param state    state pattern
     * @param endpoint endpoint pattern
     * @param action   action pattern
     * @return the scope
     */
    public static Scope of(String stage, String state, String endpoint, String action) {
        return new Scope(open(stage), open(state), open(endpoint), open(action));
    }

    /**
     * Scope open on everything but the stage.
     *
     * @param stage stage pattern
     * @return the scope
     */
    public static Scope stage(String stage) {
        return of(stage, null, null, null);
    }

    /**
     * Scope open on everything but the state.
     *
     * @param state state pattern
     * @return the scope
     */
    public static Scope state(String state) {
        return of(null, state, null, null);
    }

    /**
     * Scope open on everything but the endpoint.
     *
     * @param endpoint endpoint pattern
     * @return the scope
     */
    public static Scope endpoint(String endpoint) {
        return of(null, null, endpoint, null);
    }

    /**
     * Scope open on everything but the action.
     *
     * @param action action pattern
     * @return the scope
     */
    public static Scope action(String action) {
        return of(null, null, null, action);
    }

    /**
     * Narrows this scope to a stage.
     *
     * @param pattern stage pattern
     * @return the new scope
     */
    public Scope andStage(String pattern) {
        return new Scope(open(pattern), state, endpoint, action);
    }

    /**
     * Narrows this scope to a state.
     *
     * @param pattern state pattern
     * @return the new scope
     */
    public Scope andState(String pattern) {
        return new Scope(stage, open(pattern), endpoint, action);
    }

    /**
     * Narrows this scope to an endpoint.
     *
     * @param pattern endpoint pattern
     * @return the new scope
     */
    public Scope andEndpoint(String pattern) {
        return new Scope(stage, state, open(pattern), action);
    }

    /**
     * Narrows this scope to an action.
     *
     * @param pattern action pattern
     * @return the new scope
     */
    public Scope andAction(String pattern) {
        return new Scope(stage, state, endpoint, open(pattern));
    }

    /**
     * Whether the context falls inside this scope.
     *
     * @param ctx the context
     * @return true when all four coordinates match
     */
    public boolean matches(ValidationContext ctx) {
        return stageGlob.matches(ctx.stage()) && stateGlob.matches(ctx.state())
                && endpointGlob.matches(ctx.endpoint()) && actionGlob.matches(ctx.action());
    }

    /**
     * How many coordinates are constrained; a more specific scope wins when overrides compete.
     *
     * @return 0 to 4
     */
    public int specificity() {
        int n = 0;
        for (String s : new String[]{stage, state, endpoint, action}) {
            if (!"*".equals(s)) {
                n++;
            }
        }
        return n;
    }

    private static String open(String pattern) {
        return pattern == null || pattern.isBlank() ? "*" : pattern;
    }

    @Override
    public String toString() {
        return "Scope[stage=" + stage + ", state=" + state + ", endpoint=" + endpoint + ", action=" + action + "]";
    }
}
