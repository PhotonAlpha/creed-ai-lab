package com.creed.resource.migrationrisk.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A first draft of Velocity lines in FreeMarker syntax, shown next to a TEMPLATE_CONVERTED item as a suggestion —
 * never applied. Covers what templates mostly use: references ({@code $x.y}, {@code $!x}, {@code ${x}}),
 * {@code #if / #elseif / #else / #end}, {@code #foreach}, {@code #set}, {@code ##} comments. Macros, {@code #parse}
 * and Velocity tools pass through unchanged. The whole file is converted so each {@code #end} closes the right
 * directive; callers pick the lines they need.
 */
final class VelocityToFreemarker {

    private static final Pattern DIRECTIVE = Pattern.compile("#\\{?(foreach|elseif|if|else|end|set)\\b\\}?");
    private static final Pattern FOREACH = Pattern.compile("\\s*\\$!?\\{?(\\w+)\\}?\\s+in\\s+(.+)");
    private static final Pattern SET = Pattern.compile("\\s*\\$!?\\{?([\\w.]+)\\}?\\s*=\\s*(.+)");
    private static final Pattern QUIET = Pattern.compile("\\$!\\{([\\w.()\\[\\]\"']+)\\}|\\$!([a-zA-Z_][\\w.]*(?:\\([^)]*\\))?)");
    private static final Pattern BRACED = Pattern.compile("\\$\\{([\\w.()\\[\\]\"']+)\\}");
    private static final Pattern PLAIN = Pattern.compile("\\$([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_]\\w*(?:\\([^)]*\\))?)*)");
    /** {@code ${x.y()}} (already converted) or a bare {@code $x.y} / {@code $!x}. */
    private static final Pattern REF_IN_EXPR = Pattern.compile("\\$!?\\{([^}]*)}|\\$!?([a-zA-Z_][\\w.]*(?:\\([^)]*\\))?)");

    private VelocityToFreemarker() {
    }

    static List<String> convert(List<String> lines) {
        Deque<String> open = new ArrayDeque<>();
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("##")) {
                out.add(line.substring(0, line.length() - trimmed.length()) + "<#-- " + trimmed.substring(2).trim() + " -->");
                continue;
            }
            // references first: directive output carries ${...} only where FreeMarker wants it
            String text = references(line);
            StringBuilder sb = new StringBuilder();
            Matcher m = DIRECTIVE.matcher(text);
            int from = 0;
            while (m.find(from)) {
                sb.append(text, from, m.start());
                int end = m.end();
                String raw = null;
                int paren = skipSpaces(text, end);
                if (!m.group(1).equals("else") && !m.group(1).equals("end") && paren < text.length() && text.charAt(paren) == '(') {
                    int close = closing(text, paren);
                    if (close > 0) {
                        raw = text.substring(paren + 1, close);
                        end = close + 1;
                    }
                }
                sb.append(directive(m.group(1), raw, open, m.group()));
                from = end;
            }
            sb.append(text.substring(from));
            out.add(sb.toString());
        }
        return out;
    }

    private static String directive(String name, String raw, Deque<String> open, String original) {
        return switch (name) {
            case "foreach" -> {
                Matcher f = FOREACH.matcher(raw == null ? "" : raw);
                open.push("list");
                yield f.matches() ? "<#list " + expr(f.group(2)) + " as " + f.group(1) + ">" : "<#list " + expr(nz(raw)) + ">";
            }
            case "if" -> {
                open.push("if");
                yield "<#if " + expr(nz(raw)) + ">";
            }
            case "elseif" -> "<#elseif " + expr(nz(raw)) + ">";
            case "else" -> "<#else>";
            case "end" -> "</#" + (open.isEmpty() ? "if" : open.pop()) + ">";
            default -> {
                Matcher set = SET.matcher(raw == null ? "" : raw);
                yield set.matches() ? "<#assign " + set.group(1) + " = " + expr(set.group(2)) + ">" : original;
            }
        };
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Index of the ')' closing the '(' at {@code open}; -1 when unbalanced. */
    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** {@code $!x} -> {@code ${x!}}, {@code $x.y} -> {@code ${x.y}}; directive arguments are handled apart. */
    private static String references(String line) {
        String s = QUIET.matcher(line).replaceAll(r -> Matcher.quoteReplacement("${" + (r.group(1) != null ? r.group(1) : r.group(2)) + "!}"));
        s = BRACED.matcher(s).replaceAll(r -> Matcher.quoteReplacement("\u0000{" + r.group(1) + "}"));
        s = PLAIN.matcher(s).replaceAll(r -> Matcher.quoteReplacement("${" + r.group(1) + "}"));
        return s.replace('\u0000', '$');
    }

    /** Inside a directive: references lose their {@code $} — {@code #if($a.b)} is {@code <#if a.b>}. */
    private static String expr(String e) {
        return REF_IN_EXPR.matcher(e).replaceAll(r -> {
            String ref = r.group(1) != null ? r.group(1) : r.group(2);
            return Matcher.quoteReplacement(ref.endsWith("!") ? ref.substring(0, ref.length() - 1) : ref);
        }).trim();
    }
}
