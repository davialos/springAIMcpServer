package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.environment.EnvironmentIdentity;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Bootstrap API for the admin UI: who the caller is, which roles they hold, and which capabilities the
 * environment allows. The UI reads this once at start-up to decide which screens to show (LLD-12 §2:
 * authoring and introspection are off in production). It is a convenience for the UI only; every action is
 * still authorized server-side.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/me")
public final class MeAdminController {

    /**
     * The caller.
     *
     * @param principalId    framework principal id
     * @param type           USER, GROUP or SERVICE_ACCOUNT
     * @param displayName    display name, if known
     * @param globalRoles    roles held installation-wide
     * @param workspaceRoles roles per workspace id
     * @param clearance      data classification clearance
     */
    public record PrincipalView(UUID principalId, String type, @Nullable String displayName,
                                Set<String> globalRoles, Map<String, Set<String>> workspaceRoles,
                                String clearance) {}

    /**
     * The environment the UI runs against.
     *
     * @param tier           DEV, STAGING, PROD or UNKNOWN (UNKNOWN is treated as PROD)
     * @param environmentId  environment id
     * @param capabilities   every capability and whether it is enabled here
     */
    public record EnvironmentView(String tier, String environmentId, Map<String, Boolean> capabilities) {}

    /**
     * Response of {@code GET /me}.
     *
     * @param principal   the caller
     * @param environment the environment
     */
    public record MeView(PrincipalView principal, EnvironmentView environment) {}

    private final AdminApi api;
    private final EnvironmentSafetyPolicy safetyPolicy;
    private final EnvironmentSignals signals;

    MeAdminController(AdminApi api, EnvironmentSafetyPolicy safetyPolicy, EnvironmentSignals signals) {
        this.api = Objects.requireNonNull(api, "api");
        this.safetyPolicy = Objects.requireNonNull(safetyPolicy, "safetyPolicy");
        this.signals = Objects.requireNonNull(signals, "signals");
    }

    /**
     * Returns the caller and the environment capabilities.
     *
     * @param request current request
     * @return 200, or 401 when unauthenticated
     */
    @GetMapping
    public ResponseEntity<?> me(HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal p = gate.caller();
        Map<String, Set<String>> workspaceRoles = new TreeMap<>();
        p.workspaceRoles().forEach((ws, roles) -> workspaceRoles.put(ws.toString(), names(roles)));

        EnvironmentIdentity identity = safetyPolicy.identify(signals);
        Map<Capability, Boolean> enabled = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) {
            enabled.put(c, safetyPolicy.isEnabled(c, identity));
        }
        Map<String, Boolean> capabilities = new TreeMap<>();
        enabled.forEach((c, on) -> capabilities.put(c.name(), on));

        return ResponseEntity.ok(new MeView(
                new PrincipalView(p.principalId(), p.type().name(), p.displayName(), names(p.globalRoles()),
                        workspaceRoles, p.clearance().name()),
                new EnvironmentView(identity.tier().name(), identity.environmentId(), capabilities)));
    }

    private static Set<String> names(Set<FrameworkRole> roles) {
        Set<String> out = new TreeSet<>();
        roles.forEach(r -> out.add(r.name()));
        return out;
    }
}
