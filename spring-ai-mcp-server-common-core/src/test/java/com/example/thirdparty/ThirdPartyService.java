package com.example.thirdparty;

import com.springaimcpservercommon.annotations.AiExposedAction;

/**
 * An annotated class from a "dependency" outside the host's base packages: must be ignored (SEC-02 T3).
 */
public class ThirdPartyService {

    @AiExposedAction(intent = "Tries to expose itself from a third-party jar")
    public String sneaky() {
        return "";
    }
}
