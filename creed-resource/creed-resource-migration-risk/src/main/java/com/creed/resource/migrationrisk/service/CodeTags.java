package com.creed.resource.migrationrisk.service;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The two tags the item table filters on: which build module a path belongs to, and whether it is test code.
 * Both are read from the path alone — nothing is parsed — so they cost nothing on a large run.
 */
public final class CodeTags {

    public static final String ROOT_MODULE = "(root)";

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    /** Maven / Gradle test roots, and test classes kept next to main code. */
    private static final Pattern TEST = Pattern.compile(
            "(^|.*/)src/(test|it|integration-test|testFixtures)/.*|.*(Test|Tests|IT|TestCase)\\.java");

    /** Module directories, deepest first, so the first prefix match is the nearest one. */
    private final TreeSet<String> moduleDirs = new TreeSet<>((a, b) -> a.length() != b.length()
            ? Integer.compare(b.length(), a.length()) : a.compareTo(b));

    /** {@code paths}: every file of repo1 (baseline and head together, so a deleted module still has a name). */
    public CodeTags(Collection<String> paths) {
        for (String path : paths) {
            int slash = path.lastIndexOf('/');
            if (BUILD_FILES.contains(path.substring(slash + 1))) {
                moduleDirs.add(slash < 0 ? "" : path.substring(0, slash + 1));
            }
        }
    }

    public static boolean isTest(String path) {
        return path != null && TEST.matcher(path).matches();
    }

    /** The nearest enclosing module's directory name; {@link #ROOT_MODULE} at the top level or without a build file. */
    public String module(String path) {
        if (path == null) {
            return null;
        }
        for (String dir : moduleDirs) {
            if (path.startsWith(dir)) {
                if (dir.isEmpty()) {
                    return ROOT_MODULE;
                }
                String trimmed = dir.substring(0, dir.length() - 1);
                return trimmed.substring(trimmed.lastIndexOf('/') + 1);
            }
        }
        return ROOT_MODULE;
    }
}
