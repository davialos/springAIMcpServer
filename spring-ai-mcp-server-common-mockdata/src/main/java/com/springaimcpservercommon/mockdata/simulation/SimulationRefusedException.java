package com.springaimcpservercommon.mockdata.simulation;

/** A simulation was refused by the {@link SimulationGuard}; the message says which limit or rule. */
public class SimulationRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason
     */
    public SimulationRefusedException(String message) {
        super(message);
    }
}
