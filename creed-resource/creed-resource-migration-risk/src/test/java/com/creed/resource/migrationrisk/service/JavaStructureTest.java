package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaStructureTest {

    private static final String SOURCE = """
            package com.acme;

            import java.util.List;

            public class Outer {
                private int count;

                /** Javadoc is not part of the comparison. */
                public Outer(int count) {
                    this.count = count;
                }

                public String name(String first, List<String> rest, int... tail) {
                    return first + rest;
                }

                static class Inner {
                    void run() {
                    }
                }

                record Point(int x, int y) {
                    int sum() {
                        return x + y;
                    }
                }
            }
            """;

    @Test
    void typesMethodsAndKeys() {
        JavaStructure s = JavaStructure.parse(SOURCE);
        assertThat(s.types()).containsOnlyKeys("com.acme.Outer", "com.acme.Outer.Inner", "com.acme.Outer.Point");
        assertThat(s.topLevelTypes()).extracting(JavaStructure.TypeInfo::qualifiedName).containsExactly("com.acme.Outer");
        assertThat(s.type("com.acme.Outer").orElseThrow().methods())
                .containsOnlyKeys("<init>(int)", "name(String,List<String>,int...)");
        JavaStructure.MethodInfo name = s.type("com.acme.Outer").orElseThrow().method("name(String,List<String>,int...)").orElseThrow();
        assertThat(name.startLine()).isEqualTo(13);
        assertThat(name.endLine()).isEqualTo(15);
        assertThat(s.type("com.acme.Outer.Point").orElseThrow().methods()).containsOnlyKeys("sum()");
    }

    @Test
    void formattingCommentsAndJavadocAreNotChanges() {
        String reformatted = SOURCE
                .replace("/** Javadoc is not part of the comparison. */", "/** Reworded javadoc. */")
                .replace("return first + rest;", "// a comment\n        return first\n            + rest;");
        JavaStructure before = JavaStructure.parse(SOURCE);
        JavaStructure after = JavaStructure.parse(reformatted);
        assertThat(after.type("com.acme.Outer").orElseThrow().methods().values())
                .extracting(JavaStructure.MethodInfo::normalized)
                .containsExactlyElementsOf(before.type("com.acme.Outer").orElseThrow().methods().values().stream()
                        .map(JavaStructure.MethodInfo::normalized).toList());
    }

    @Test
    void aBodyEditIsAChange() {
        JavaStructure before = JavaStructure.parse(SOURCE);
        JavaStructure after = JavaStructure.parse(SOURCE.replace("return x + y;", "return x - y;"));
        assertThat(after.type("com.acme.Outer.Point").orElseThrow().method("sum()").orElseThrow().normalized())
                .isNotEqualTo(before.type("com.acme.Outer.Point").orElseThrow().method("sum()").orElseThrow().normalized());
    }
}
