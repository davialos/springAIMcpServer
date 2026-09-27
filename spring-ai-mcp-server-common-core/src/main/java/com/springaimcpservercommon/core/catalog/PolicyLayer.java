package com.springaimcpservercommon.core.catalog;

/**
 * Layers of the policy resolution chain, in merge order (LLD-03 §4). Declared in the catalog package (not in
 * {@code core.policy}) so that the effective model can carry provenance without a package cycle: the policy
 * package depends on the catalog, never the reverse.
 */
public enum PolicyLayer {
    /** L0 — code annotations; the only layer that can expose an element or name a tool. */
    CODE,
    /** L1 — classpath / file policy JSON ({@code dynamic.ai.agent.policy.locations}). */
    FILE,
    /** L2 — published, approved dashboard overlays; the only layer that may declassify (explicit flag). */
    OVERLAY,
    /** L3 — runtime kill switches (by tool name or element), cluster-wide. */
    KILL_SWITCH
}
