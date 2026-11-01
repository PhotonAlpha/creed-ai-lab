package com.creed.resource.migrationrisk.service;

/**
 * A Java file reduced to the tokens that can change behaviour: comments, whitespace and {@code import} declarations
 * dropped, string / char literals and text blocks kept verbatim. Two files with the same reduction differ only in
 * what {@link JavaStructure}'s normalized texts ignore anyway, so the analysis can skip them before parsing — and,
 * above all, before RefactoringMiner, whose time grows with every file it is given. A javax -> jakarta migration is
 * mostly such files.
 *
 * <p>Deliberately a lexer, not a parser: it runs on every differing Java file of both sides. Stricter than the
 * analysis on purpose — the package declaration and annotations count — so a skipped file is one the analysis would
 * have found nothing in.
 */
final class JavaTokens {

    private JavaTokens() {
    }

    /** True when {@code a} and {@code b} differ only in imports, comments or whitespace. */
    static boolean sameCode(String a, String b) {
        return significant(a).equals(significant(b));
    }

    static String significant(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        // inside an import declaration: everything up to its ';' is dropped
        boolean inImport = false;
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(source, i);
                if (!inImport) {
                    out.append(source, i, end).append(' ');
                }
                i = end;
            } else if (Character.isJavaIdentifierPart(c)) {
                int end = i;
                while (end < n && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                String word = source.substring(i, end);
                if (!inImport && word.equals("import")) {
                    inImport = true;
                } else if (!inImport) {
                    // a separator only between words: "int a" and "inta" must stay apart, "f( a )" and "f(a)" not
                    out.append(word).append(' ');
                }
                i = end;
            } else {
                if (inImport) {
                    inImport = c != ';';
                } else {
                    out.append(c);
                }
                i++;
            }
        }
        return out.toString();
    }

    /** Index after the literal starting at {@code start}: "...", '...' or a """ text block. */
    private static int literalEnd(String s, int start) {
        char quote = s.charAt(start);
        if (quote == '"' && s.startsWith("\"\"\"", start)) {
            int i = start + 3;
            while (i < s.length()) {
                if (s.charAt(i) == '\\') {
                    i += 2;
                } else if (s.startsWith("\"\"\"", i)) {
                    return i + 3;
                } else {
                    i++;
                }
            }
            return s.length();
        }
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote || c == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return s.length();
    }
}
