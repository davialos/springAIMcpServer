package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.Access;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the project's Spring Security setup: the {@code SecurityFilterChain} (authentication style, CSRF, request
 * matchers with {@code permitAll}/{@code authenticated}/{@code hasRole}…) and method security annotations
 * ({@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed}). It reads text and annotation trees; nothing is
 * executed, and expressions it cannot read are left unknown rather than guessed.
 */
public final class SecurityScanner {

    private static final Pattern MATCHER = Pattern.compile(
            "(?:(?:requestMatchers|antMatchers|mvcMatchers)\\s*\\(([^()]*)\\)|(anyRequest)\\s*\\(\\s*\\))"
                    + "\\s*\\.\\s*(permitAll|authenticated|denyAll|hasRole|hasAnyRole|hasAuthority|hasAnyAuthority)"
                    + "\\s*\\(([^()]*)\\)");
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern HTTP_METHOD = Pattern.compile("HttpMethod\\s*\\.\\s*([A-Z]+)");
    private static final Pattern LOGIN_PAGE = Pattern.compile("(?:loginPage|loginProcessingUrl)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CSRF_OFF = Pattern.compile("csrf\\s*\\([^;]{0,80}?disable|csrf\\s*\\(\\s*\\)\\s*\\.\\s*disable");
    private static final Pattern EXPR_ROLES = Pattern.compile(
            "(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority)\\s*\\(([^()]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    private final Consumer<String> log;

    /**
     * Creates a scanner.
     *
     * @param log receives a note about what was found
     */
    public SecurityScanner(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Scans a project.
     *
     * @param projectDir project root
     * @param settings   its Spring settings (OAuth2 resource server properties)
     * @return the model; {@link SecurityModel#none()} when the project does not use Spring Security
     */
    public SecurityModel scan(Path projectDir, ProjectSettings settings) {
        boolean dependency = ProjectFiles.declares(projectDir, "spring-boot-starter-security")
                || ProjectFiles.declares(projectDir, "spring-security-web")
                || ProjectFiles.declares(projectDir, "spring-boot-starter-oauth2-resource-server");
        StringBuilder config = new StringBuilder();
        for (Path file : ProjectFiles.javaSources(projectDir)) {
            try {
                String text = Files.readString(file);
                if (text.contains("SecurityFilterChain") || text.contains("EnableWebSecurity")
                        || text.contains("WebSecurityConfigurerAdapter")) {
                    config.append(text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ")).append('\n');
                }
            } catch (IOException e) {
                log.accept("security: cannot read " + file + ": " + e.getMessage());
            }
        }
        String text = config.toString();
        boolean resourceServer = text.contains("oauth2ResourceServer") || text.contains("oauth2Login")
                || settings.properties().keySet().stream().anyMatch(k -> k.startsWith("spring.security.oauth2.resourceserver"));
        if (!dependency && text.isEmpty() && !resourceServer) {
            return SecurityModel.none();
        }
        SecurityModel.Style style = resourceServer ? SecurityModel.Style.BEARER
                : text.contains("httpBasic") ? SecurityModel.Style.BASIC
                : text.contains("formLogin") ? SecurityModel.Style.FORM
                : SecurityModel.Style.BASIC; // Boot's default chain: basic and form login; basic is scriptable
        Matcher login = LOGIN_PAGE.matcher(text);
        String loginPage = login.find() ? login.group(1) : null;
        boolean csrf = !CSRF_OFF.matcher(text).find();
        List<SecurityModel.Rule> rules = rules(text);
        log.accept("security: " + style.name().toLowerCase(Locale.ROOT) + " authentication, csrf "
                + (csrf ? "on" : "off") + ", " + rules.size() + " request-matcher rules");
        return new SecurityModel(style, csrf, loginPage, rules);
    }

    private static List<SecurityModel.Rule> rules(String text) {
        List<SecurityModel.Rule> out = new ArrayList<>();
        Matcher m = MATCHER.matcher(text);
        while (m.find()) {
            Access access = switch (m.group(3)) {
                case "permitAll" -> Access.open();
                case "authenticated" -> Access.authenticated();
                case "denyAll" -> Access.denied();
                default -> Access.roles(quoted(m.group(4)));
            };
            Set<HttpMethod> methods = new LinkedHashSet<>();
            List<String> patterns = new ArrayList<>();
            if (m.group(2) != null) {
                patterns.add("/**");
            } else {
                Matcher hm = HTTP_METHOD.matcher(m.group(1));
                while (hm.find()) {
                    methods.add(HttpMethod.parse(hm.group(1)));
                }
                Matcher sm = STRING.matcher(m.group(1));
                while (sm.find()) {
                    patterns.add(sm.group(1));
                }
            }
            if (!patterns.isEmpty()) {
                out.add(new SecurityModel.Rule(methods, patterns, access));
            }
        }
        return out;
    }

    private static List<String> quoted(String args) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING.matcher(args);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ── method security ────────────────────────────────────────────────────────────────────────────────

    /**
     * Access declared by method security annotations; the method's own annotation wins over its class's.
     *
     * @param trees  parsed sources (for annotation values)
     * @param method modifiers of the handler method
     * @param owner  modifiers of its class
     * @return the access, or {@code null} when no annotation says anything readable
     */
    static @Nullable Access fromAnnotations(SourceTrees trees, ModifiersTree method, ModifiersTree owner) {
        Access own = fromModifiers(trees, method);
        return own != null ? own : fromModifiers(trees, owner);
    }

    private static @Nullable Access fromModifiers(SourceTrees trees, ModifiersTree mods) {
        Optional<AnnotationTree> pre = SourceTrees.annotation(mods, "PreAuthorize");
        if (pre.isPresent()) {
            return trees.string(pre.get(), "value").map(SecurityScanner::fromExpression).orElse(null);
        }
        Optional<AnnotationTree> secured = SourceTrees.annotation(mods, "Secured", "RolesAllowed");
        if (secured.isPresent()) {
            List<String> roles = trees.strings(secured.get(), "value");
            if (roles.size() == 1 && roles.getFirst().equals("IS_AUTHENTICATED_ANONYMOUSLY")) {
                return Access.open();
            }
            return roles.isEmpty() ? null : Access.roles(roles);
        }
        if (SourceTrees.has(mods, "PermitAll")) {
            return Access.open();
        }
        if (SourceTrees.has(mods, "DenyAll")) {
            return Access.denied();
        }
        return null;
    }

    /**
     * Reads a {@code @PreAuthorize} expression: {@code hasRole}/{@code hasAnyRole}/{@code hasAuthority} give roles
     * (combined conditions such as {@code hasRole('A') and #id == principal.id} keep the roles),
     * {@code permitAll()} is open, {@code isAuthenticated()} any caller.
     *
     * @param expression SpEL text
     * @return the access, or {@code null} when it names no role and is not one of the simple forms
     */
    static @Nullable Access fromExpression(String expression) {
        if (expression.contains("permitAll")) {
            return Access.open();
        }
        if (expression.contains("denyAll")) {
            return Access.denied();
        }
        List<String> roles = new ArrayList<>();
        Matcher m = EXPR_ROLES.matcher(expression);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(2));
            while (q.find()) {
                roles.add(q.group(1));
            }
        }
        if (!roles.isEmpty()) {
            return Access.roles(roles);
        }
        return expression.contains("isAuthenticated") || expression.contains("authenticated")
                ? Access.authenticated() : null;
    }
}
