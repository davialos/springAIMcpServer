package com.springaimcpservercommon.core.guard;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in {@link PromptValidator} that rejects prompts trying to manipulate the assistant or attack the host
 * (LLD-06 §8, F-76): prompt injection, jailbreaks, system-prompt extraction, SQL/script/command injection, path
 * traversal, bulk data exfiltration and hidden or encoded payloads.
 *
 * <p><b>How it decides.</b> The prompt is normalised (Unicode NFKC, invisible characters removed, lower case,
 * whitespace collapsed; a second variant undoes letter spacing "i g n o r e" and common digit-for-letter swaps
 * "1gn0re"). Weighted rules run over the variants; every rule that fires adds its weight once and the prompt is
 * rejected when the total reaches {@link #THRESHOLD}. High-confidence patterns weigh 1.0 on their own; weaker
 * signals weigh less and only reject in combination. Base64 blobs are decoded and checked again.
 *
 * <p>This is a deterministic first line of defence, not a guarantee: the model is still told that tool output is
 * data, tools run as the caller and writes need a reviewed proposal (ADR-0008, ADR-0009). Hosts that need a
 * classifier model add their own {@link PromptValidator}.
 *
 * <p>Stateless and thread-safe. The rejection never echoes the prompt; findings carry category names only.
 */
public final class MaliciousPromptValidator implements PromptValidator {

    /** Total weight at which a prompt is rejected. */
    public static final double THRESHOLD = 1.0;

    private static final int MAX_DECODED_BLOBS = 4;
    private static final int MAX_BLOB_CHARS = 8_192;

    private record Rule(ThreatCategory category, double weight, Pattern pattern, boolean onLeetVariant) {
    }

    private static Rule rule(ThreatCategory category, double weight, String regex) {
        return new Rule(category, weight, Pattern.compile(regex, Pattern.DOTALL), false);
    }

    /** Rules on natural-language phrasing also run on the de-obfuscated variant. */
    private static Rule phrase(ThreatCategory category, double weight, String regex) {
        return new Rule(category, weight, Pattern.compile(regex, Pattern.DOTALL), true);
    }

    private static final List<Rule> RULES = List.of(
            // ── prompt injection ──────────────────────────────────────────────────────────────────────
            phrase(ThreatCategory.PROMPT_INJECTION, 1.0,
                    "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}"
                            + "\\b(all|any|your|previous|prior|above|earlier|preceding|system|original|initial)\\b"
                            + ".{0,40}\\b(instructions?|prompts?|rules?|directives?|guidelines?|guardrails?|"
                            + "constraints?|polic(y|ies)|restrictions?)\\b"),
            phrase(ThreatCategory.PROMPT_INJECTION, 0.6,
                    "\\b(new|updated|revised|real|actual)\\s+(system\\s+)?(instructions?|rules?|directives?)\\s*:"),
            phrase(ThreatCategory.PROMPT_INJECTION, 0.6, "\\byou\\s+are\\s+(now|no\\s+longer)\\b"),
            phrase(ThreatCategory.PROMPT_INJECTION, 0.4,
                    "\\bfrom\\s+now\\s+on\\b.{0,40}\\b(you|act|respond|answer|behave)\\b"),
            phrase(ThreatCategory.PROMPT_INJECTION, 1.0,
                    "\\b(act|behave|pretend|roleplay|role-play)\\s+(as|like|to\\s+be)\\b.{0,30}"
                            + "\\b(unrestricted|unfiltered|uncensored|admin(istrator)?|root|superuser|sysadmin|"
                            + "developer|system|jailbroken|evil)\\b"),
            rule(ThreatCategory.PROMPT_INJECTION, 1.0,
                    "<\\|?\\s*(im_start|im_end|system|endoftext|assistant)\\s*\\|?>|\\[/?(inst|sys|system)\\]"
                            + "|<<\\s*sys\\s*>>|(^|\\n)\\s*#{2,}\\s*(system|instruction)s?\\b"),
            rule(ThreatCategory.PROMPT_INJECTION, 0.6, "(^|\\n)\\s*(system|assistant)\\s*:"),
            // ── jailbreak ────────────────────────────────────────────────────────────────────────────
            phrase(ThreatCategory.JAILBREAK, 1.0,
                    "\\bdo\\s+anything\\s+now\\b|\\bdeveloper\\s+mode\\b|\\bgod\\s+mode\\b|\\bjailbr(ea|oke)k"
                            + "|\\bdan\\s+mode\\b"),
            phrase(ThreatCategory.JAILBREAK, 0.6,
                    "\\b(without|no|remove|disable|turn\\s+off)\\s+(any\\s+|all\\s+|your\\s+)?"
                            + "(restrictions?|filters?|limitations?|guardrails?|censorship|safety|content\\s+polic(y|ies))\\b"),
            // ── system prompt extraction ─────────────────────────────────────────────────────────────
            phrase(ThreatCategory.SYSTEM_PROMPT_EXTRACTION, 1.0,
                    "\\b(system|initial|hidden|original|developer|secret|internal)\\s+"
                            + "(prompt|instructions?|message|directives?)\\b.{0,40}"
                            + "\\b(reveal|show|print|display|repeat|output|tell|give|leak|dump|share|verbatim)\\b"
                            + "|\\b(reveal|show|print|display|repeat|output|tell|give|leak|dump|share)\\b.{0,30}"
                            + "\\b(system|initial|hidden|original|developer|secret|internal)\\s+"
                            + "(prompt|instructions?|message|directives?)\\b"),
            phrase(ThreatCategory.SYSTEM_PROMPT_EXTRACTION, 1.0,
                    "\\b(reveal|show|print|display|repeat|output|tell|give|leak|dump|what\\s+(are|were|is))\\b.{0,20}"
                            + "\\byour\\s+(instructions|prompt|system\\s+message|configuration)\\b"),
            phrase(ThreatCategory.SYSTEM_PROMPT_EXTRACTION, 0.6,
                    "\\b(reveal|show|print|display|repeat|output|tell|give|leak|dump|what\\s+(are|were|is))\\b.{0,20}"
                            + "\\byour\\s+(rules|guidelines|constraints)\\b"),
            phrase(ThreatCategory.SYSTEM_PROMPT_EXTRACTION, 1.0,
                    "\\b(repeat|print|output|echo)\\b.{0,20}\\b(everything|all|text|words)\\b.{0,20}"
                            + "\\b(above|before|preceding|so\\s+far)\\b"),
            // ── SQL injection ────────────────────────────────────────────────────────────────────────
            rule(ThreatCategory.SQL_INJECTION, 1.0, "'\\s*(or|and)\\s+'?\\w+'?\\s*(=|like)\\s*'?\\w+"),
            rule(ThreatCategory.SQL_INJECTION, 1.0, "\\bunion\\s+(all\\s+)?select\\b"),
            rule(ThreatCategory.SQL_INJECTION, 1.0,
                    ";\\s*(drop|delete|truncate|alter|insert|update|create|grant|revoke|exec(ute)?|shutdown)\\b"),
            rule(ThreatCategory.SQL_INJECTION, 1.0, "\\b(drop|truncate)\\s+(table|database|schema)\\b"),
            rule(ThreatCategory.SQL_INJECTION, 1.0,
                    "\\bpg_sleep\\s*\\(|\\bsleep\\s*\\(\\s*\\d+\\s*\\)|\\bwaitfor\\s+delay\\b|\\bbenchmark\\s*\\("
                            + "|\\bxp_cmdshell\\b|\\bload_file\\s*\\(|\\binto\\s+(out|dump)file\\b"),
            rule(ThreatCategory.SQL_INJECTION, 0.6, "'\\s*;?\\s*--|'\\s*#|/\\*.*?\\*/"),
            rule(ThreatCategory.SQL_INJECTION, 0.6,
                    "\\b(information_schema|pg_catalog|pg_shadow|sys\\.tables|sysobjects|sqlite_master|mysql\\.user)\\b"),
            // ── script / markup injection ───────────────────────────────────────────────────────────
            rule(ThreatCategory.SCRIPT_INJECTION, 1.0, "<\\s*script\\b|javascript\\s*:|vbscript\\s*:"),
            rule(ThreatCategory.SCRIPT_INJECTION, 1.0,
                    "<[^>]{0,200}\\bon(error|load|click|mouseover|focus|submit)\\s*="),
            rule(ThreatCategory.SCRIPT_INJECTION, 0.6,
                    "<\\s*(iframe|object|embed|svg|img|meta|link|base)\\b|\\bdata\\s*:\\s*text/html"),
            // ── command injection / path traversal ──────────────────────────────────────────────────
            rule(ThreatCategory.COMMAND_INJECTION, 1.0,
                    "(;|&&|\\|\\|)\\s*(rm|curl|wget|nc|ncat|netcat|bash|sh|zsh|powershell|pwsh|cmd|chmod|chown|"
                            + "python\\d?|perl|ruby|php|nslookup|whoami|uname|scp|ssh)\\s"),
            rule(ThreatCategory.COMMAND_INJECTION, 1.0,
                    "\\brm\\s+-[a-z]*r[a-z]*f?\\b|/etc/(passwd|shadow|hosts)\\b|\\bcmd(\\.exe)?\\s+/c\\b"
                            + "|\\bpowershell(\\.exe)?\\s+-(e|enc|encodedcommand|c|command)\\b"),
            rule(ThreatCategory.COMMAND_INJECTION, 0.6, "\\$\\([^)]{1,200}\\)|\\$\\{[^}]{1,200}\\}"),
            rule(ThreatCategory.PATH_TRAVERSAL, 1.0, "(\\.\\./){2,}|(\\.\\.\\\\){2,}|%2e%2e(%2f|%5c)|\\.\\.%2f"),
            // ── data exfiltration ────────────────────────────────────────────────────────────────────
            phrase(ThreatCategory.DATA_EXFILTRATION, 1.0,
                    "\\b(all|every|each|entire|complete|full|list\\s+of)\\b.{0,30}"
                            + "\\b(passwords?|password\\s+hash(es)?|credentials?|api\\s*keys?|secret\\s+keys?|secrets|"
                            + "access\\s+tokens?|private\\s+keys?|(credit\\s*)?card\\s+numbers?|cvvs?|ssns?|"
                            + "social\\s+security\\s+numbers?)\\b"),
            phrase(ThreatCategory.DATA_EXFILTRATION, 0.6,
                    "\\b(dump|exfiltrate|extract|download|export|scrape)\\b.{0,30}\\b(entire|whole|all|full|every)\\b"
                            + ".{0,20}\\b(database|db|tables?|users?|customers?|records|rows|accounts?)\\b"),
            phrase(ThreatCategory.DATA_EXFILTRATION, 0.6,
                    "\\b(other|another|all|every)\\s+(users?|customers?|employees?|tenants?|accounts?)('s|s')?\\s+"
                            + "(data|records|passwords?|details|information|messages|conversations|history)\\b"),
            phrase(ThreatCategory.DATA_EXFILTRATION, 0.5,
                    "\\b(send|post|upload|forward|email|e-mail|transmit|leak)\\b.{0,60}\\b(to|at|into)\\b\\s*"
                            + "(https?://|ftp://|\\S+@\\S+\\.[a-z]{2,}|webhook)"),
            rule(ThreatCategory.DATA_EXFILTRATION, 0.6, "!\\[[^\\]]{0,100}\\]\\(\\s*https?://[^)]*[?&][^)]*=")
    );

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\u180E]");
    private static final Pattern BIDI = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");
    private static final Pattern LETTER_SPACED = Pattern.compile("\\b(?:\\p{L} ){3,}\\p{L}\\b");
    private static final Pattern BASE64_BLOB = Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}");
    private static final Pattern URL_ENCODED_RUN = Pattern.compile("(%[0-9a-fA-F]{2}){8,}");
    private static final Pattern HEX_ESCAPE_RUN = Pattern.compile("(\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}){8,}");

    /**
     * Result of assessing a prompt.
     *
     * @param score      total weight of the rules that fired
     * @param categories categories of the rules that fired
     */
    public record Assessment(double score, Set<ThreatCategory> categories) {

        /** Copies the categories. */
        public Assessment {
            categories = categories.isEmpty() ? Set.of() : Set.copyOf(categories);
        }

        /**
         * Whether the prompt should be rejected.
         *
         * @return {@code true} when the score reaches {@link #THRESHOLD}
         */
        public boolean malicious() {
            return score >= THRESHOLD;
        }
    }

    @Override
    public PromptVerdict validate(PromptValidationRequest request) {
        if (!request.policy().threatDetection()) {
            return PromptVerdict.allow();
        }
        Assessment a = assess(request.prompt());
        if (!a.malicious()) {
            return PromptVerdict.allow();
        }
        List<String> findings = a.categories().stream().map(Enum::name).sorted().toList();
        return PromptVerdict.reject("input_malicious",
                "Your message looks like an attempt to change how the assistant works or to access data or systems "
                        + "it must not reach, so it was not processed. Please rephrase your request.",
                findings);
    }

    @Override
    public String name() {
        return "malicious-prompt";
    }

    /**
     * Scores a prompt without deciding (exposed for tests and for hosts that want the categories).
     *
     * @param prompt the user's message
     * @return the assessment
     */
    public Assessment assess(String prompt) {
        EnumSet<ThreatCategory> categories = EnumSet.noneOf(ThreatCategory.class);
        double score = 0;

        // hidden characters are a signal on their own, before normalisation removes them
        if (prompt.codePoints().anyMatch(cp -> cp >= 0xE0000 && cp <= 0xE007F) || BIDI.matcher(prompt).find()) {
            categories.add(ThreatCategory.OBFUSCATION);
            score += 1.0;
        }
        long zeroWidth = ZERO_WIDTH.matcher(prompt).results().count();
        if (zeroWidth >= 3) {
            categories.add(ThreatCategory.OBFUSCATION);
            score += 0.6;
        }
        if (URL_ENCODED_RUN.matcher(prompt).find() || HEX_ESCAPE_RUN.matcher(prompt).find()) {
            categories.add(ThreatCategory.OBFUSCATION);
            score += 0.4;
        }

        String normal = normalize(prompt);
        String deobfuscated = deobfuscate(normal);
        score += scoreRules(normal, deobfuscated, categories);

        // encoded payloads: decode base64 blobs and look for the same attacks inside
        Matcher blob = BASE64_BLOB.matcher(prompt);
        int decoded = 0;
        while (blob.find() && decoded < MAX_DECODED_BLOBS) {
            String candidate = blob.group();
            if (candidate.length() > MAX_BLOB_CHARS) {
                continue;
            }
            decoded++;
            String text = decodeBase64(candidate);
            if (text == null) {
                continue;
            }
            EnumSet<ThreatCategory> inner = EnumSet.noneOf(ThreatCategory.class);
            String innerNormal = normalize(text);
            if (scoreRules(innerNormal, deobfuscate(innerNormal), inner) >= THRESHOLD) {
                categories.addAll(inner);
                categories.add(ThreatCategory.OBFUSCATION);
                score += 1.0;
            }
        }
        return new Assessment(score, categories);
    }

    private static double scoreRules(String normal, String deobfuscated, Set<ThreatCategory> categories) {
        double score = 0;
        for (Rule r : RULES) {
            boolean hit = r.pattern().matcher(normal).find()
                    || (r.onLeetVariant() && !deobfuscated.equals(normal) && r.pattern().matcher(deobfuscated).find());
            if (hit) {
                score += r.weight();
                categories.add(r.category());
            }
        }
        return score;
    }

    /** NFKC, invisible characters removed, lower case, whitespace runs collapsed (newlines kept). */
    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = BIDI.matcher(s).replaceAll("");
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().filter(cp -> cp < 0xE0000 || cp > 0xE007F).forEach(sb::appendCodePoint);
        s = sb.toString().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\\R", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(WHITESPACE.matcher(line).replaceAll(" ").strip());
        }
        return out.toString();
    }

    /** Undoes letter spacing ("i g n o r e") and common digit/symbol-for-letter swaps ("1gn0re"). */
    static String deobfuscate(String normal) {
        String s = LETTER_SPACED.matcher(normal).replaceAll(m -> m.group().replace(" ", ""));
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean inWord = (i > 0 && Character.isLetter(s.charAt(i - 1)))
                    || (i + 1 < s.length() && Character.isLetter(s.charAt(i + 1)));
            sb.append(inWord ? switch (c) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            } : c);
        }
        return sb.toString();
    }

    private static @Nullable String decodeBase64(String candidate) {
        try {
            byte[] bytes = Base64.getDecoder().decode(candidate);
            String text = new String(bytes, StandardCharsets.UTF_8);
            long printable = text.chars().filter(c -> c >= 0x20 && c < 0x7F || c == '\n' || c == '\t').count();
            return printable >= text.length() * 0.9 ? text : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
