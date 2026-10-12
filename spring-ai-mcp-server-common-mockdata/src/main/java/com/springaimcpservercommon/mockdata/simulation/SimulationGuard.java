package com.springaimcpservercommon.mockdata.simulation;

/** Decides whether a simulation may run. A load generator is a weapon: the default guard is strict. */
@FunctionalInterface
public interface SimulationGuard {

    /**
     * Checks a simulation before it starts.
     *
     * @param spec what is about to run
     * @throws SimulationRefusedException with the reason when it must not run
     */
    void check(SimulationSpec spec);
}
