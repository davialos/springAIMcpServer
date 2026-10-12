package com.springaimcpservercommon.mockdata.simulation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** A running simulation: watch it, stop it, wait for its report. */
public interface SimulationHandle {

    /**
     * Requests sent so far.
     *
     * @return count
     */
    long requestsSoFar();

    /**
     * Failed requests so far.
     *
     * @return count
     */
    long failuresSoFar();

    /** Stops the virtual users; the report is completed with {@code cancelled = true}. */
    void cancel();

    /**
     * The report, completed when the run ends.
     *
     * @return the future
     */
    CompletableFuture<SimulationReport> result();

    /**
     * Waits for the report.
     *
     * @return the report
     */
    default SimulationReport await() {
        try {
            return result().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
            throw new IllegalStateException("interrupted while waiting for the simulation", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("the simulation failed", e.getCause());
        }
    }
}
