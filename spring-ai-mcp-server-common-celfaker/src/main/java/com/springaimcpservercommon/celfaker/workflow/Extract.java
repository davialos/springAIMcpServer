package com.springaimcpservercommon.celfaker.workflow;

/**
 * Takes a value out of a step's response into a variable later steps can use.
 *
 * @param name variable name ({@code orderId}); referenced as {@code {{stepId.orderId}}}
 * @param from {@code body.<path>} (dotted, {@code [n]} indexes), {@code header.<Name>} or {@code status}
 */
public record Extract(String name, String from) {
}
