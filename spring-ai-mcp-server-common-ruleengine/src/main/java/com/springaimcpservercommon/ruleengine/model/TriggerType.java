package com.springaimcpservercommon.ruleengine.model;

/** The kind of place in an application that triggers a rule group. */
public enum TriggerType {
    /** An action on a form (submit, approve, add, buy ...). */
    FORM_ACTION,
    /** A change of one field of a form. */
    FORM_FIELD
}
