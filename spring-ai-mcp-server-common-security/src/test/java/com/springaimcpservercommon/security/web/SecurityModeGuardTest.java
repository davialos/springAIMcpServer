package com.springaimcpservercommon.security.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityModeGuardTest {

    private static final HostAuthenticationCapabilities NONE = HostAuthenticationCapabilities.none();
    private static final HostAuthenticationCapabilities JWT =
            new HostAuthenticationCapabilities(new Object(), null, false, List.of(), false);
    private static final HostAuthenticationCapabilities FORM =
            new HostAuthenticationCapabilities(null, null, false, List.of(), true);

    @Test
    void noSecurityDisablesEveryPlane() {
        var d = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(NONE, false, false, false, false));
        assertThat(d.adminPlaneEnabled()).isFalse();
        assertThat(d.dataPlaneEnabled()).isFalse();
        assertThat(d.mcpEnabled()).isFalse();
        assertThat(d.messages()).hasSize(3);
    }

    @Test
    void allowInsecureIsIgnoredOutsideDev() {
        var prod = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(NONE, false, false, true, false));
        assertThat(prod.adminPlaneEnabled()).isFalse();
        assertThat(prod.insecure()).isFalse();
        assertThat(prod.messages()).anyMatch(m -> m.contains("ignored outside the DEV tier"));

        var dev = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(NONE, false, false, true, true));
        assertThat(dev.adminPlaneEnabled()).isTrue();
        assertThat(dev.insecure()).isTrue();
    }

    @Test
    void planesFollowAvailableMechanisms() {
        var jwt = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(JWT, false, false, false, false));
        assertThat(jwt.adminPlaneEnabled() && jwt.dataPlaneEnabled() && jwt.mcpEnabled()).isTrue();

        var form = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(FORM, false, false, false, false));
        assertThat(form.adminPlaneEnabled()).isTrue();
        assertThat(form.dataPlaneEnabled()).isFalse();
        assertThat(form.mcpEnabled()).isFalse();

        var formWithSessionsAndKeys = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(FORM, true, true, false, false));
        assertThat(formWithSessionsAndKeys.dataPlaneEnabled()).isTrue();
        assertThat(formWithSessionsAndKeys.mcpEnabled()).isTrue();

        var keysOnly = SecurityModeGuard.evaluate(new SecurityModeGuard.Input(NONE, true, false, false, false));
        assertThat(keysOnly.adminPlaneEnabled()).isFalse();
        assertThat(keysOnly.dataPlaneEnabled()).isTrue();
    }
}
