package com.springaimcpservercommon.loadtest.cli;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@code --name value} / {@code --flag} parser; repeated options accumulate; everything after
 * {@code --} is passed through.
 */
final class CliArgs {

    private final String command;
    private final Map<String, List<String>> options = new LinkedHashMap<>();
    private final List<String> passThrough = new ArrayList<>();

    private CliArgs(String command) {
        this.command = command;
    }

    static CliArgs parse(String[] args, Set<String> flags) {
        if (args.length == 0) {
            return new CliArgs("help");
        }
        CliArgs out = new CliArgs(args[0]);
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--")) {
                out.passThrough.addAll(List.of(args).subList(i + 1, args.length));
                break;
            }
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + a);
            }
            String name = a.substring(2);
            String value;
            int eq = name.indexOf('=');
            if (eq > 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (flags.contains(name)) {
                value = "true";
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--" + name + " needs a value");
                }
                value = args[++i];
            }
            out.options.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    String command() {
        return command;
    }

    @Nullable String get(String name) {
        List<String> v = options.get(name);
        return v == null ? null : v.getLast();
    }

    String get(String name, String fallback) {
        String v = get(name);
        return v == null ? fallback : v;
    }

    List<String> all(String name) {
        return options.getOrDefault(name, List.of());
    }

    boolean flag(String name) {
        return "true".equals(get(name));
    }

    int integer(String name, int fallback) {
        String v = get(name);
        return v == null ? fallback : Integer.parseInt(v);
    }

    List<String> passThrough() {
        return passThrough;
    }

    Set<String> names() {
        return options.keySet();
    }
}
