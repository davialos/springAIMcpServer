package com.springaimcpservercommon.ruleengine.domain;

/** Type of a sys-object attribute; decides how a supplied value is converted for CEL. */
public enum DataType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, STRING_LIST, INTEGER_LIST, DECIMAL_LIST
}
