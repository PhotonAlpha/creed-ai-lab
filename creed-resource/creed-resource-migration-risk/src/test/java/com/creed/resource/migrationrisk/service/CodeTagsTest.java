package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeTagsTest {

    @Test
    void nearestBuildFileNamesTheModule() {
        CodeTags tags = new CodeTags(List.of("pom.xml", "order/pom.xml", "order/api/build.gradle.kts",
                "order/src/main/java/A.java", "order/api/src/main/java/B.java", "docs/readme.md"));
        assertThat(tags.module("order/src/main/java/A.java")).isEqualTo("order");
        assertThat(tags.module("order/api/src/main/java/B.java")).isEqualTo("api");
        assertThat(tags.module("docs/readme.md")).isEqualTo(CodeTags.ROOT_MODULE);
        // a module directory name that is a prefix of another is not mistaken for it
        assertThat(tags.module("order-legacy/src/main/java/C.java")).isEqualTo(CodeTags.ROOT_MODULE);
    }

    @Test
    void testCodeAndTestResources() {
        assertThat(CodeTags.isTest("order/src/test/java/com/acme/OrderTest.java")).isTrue();
        assertThat(CodeTags.isTest("src/test/resources/application.yml")).isTrue();
        assertThat(CodeTags.isTest("src/main/java/com/acme/OrderIT.java")).isTrue();
        assertThat(CodeTags.isTest("src/main/java/com/acme/Contest.java")).isFalse();
        assertThat(CodeTags.isTest("src/main/java/com/acme/TestData.java")).isFalse();
        assertThat(CodeTags.isTest(null)).isFalse();
    }
}
