package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaTokensTest {

    private static final String REPO1 = """
            package com.acme.shop;

            import javax.servlet.http.HttpServletRequest;
            import java.util.List;

            /** Old Javadoc. */
            public class Orders {
                // the old comment
                public String find(HttpServletRequest request, int a) {
                    return request.getParameter( "id" ) + a;
                }
            }
            """;

    @Test
    void importsCommentsAndFormattingDoNotCount() {
        String repo2 = """
                package com.acme.shop;
                import jakarta.servlet.http.HttpServletRequest;
                import static java.util.Objects.requireNonNull;
                /* rewritten */
                public class Orders
                {
                    public String find(HttpServletRequest request,int a) { return request.getParameter("id")+a; }
                }
                """;
        assertThat(JavaTokens.sameCode(REPO1, repo2)).isTrue();
    }

    @Test
    void codeChangesCount() {
        assertThat(JavaTokens.sameCode(REPO1, REPO1.replace("+ a", "- a"))).isFalse();
        assertThat(JavaTokens.sameCode(REPO1, REPO1.replace("package com.acme.shop", "package com.acme.store"))).isFalse();
        assertThat(JavaTokens.sameCode(REPO1, REPO1.replace("public class", "@Deprecated public class"))).isFalse();
        // words stay apart: "int a" is not "inta"
        assertThat(JavaTokens.significant("int a;")).isNotEqualTo(JavaTokens.significant("inta;"));
    }

    @Test
    void literalsAreKeptVerbatim() {
        assertThat(JavaTokens.sameCode("String s = \"a  b\";", "String s = \"a b\";")).isFalse();
        assertThat(JavaTokens.sameCode("String s = \"// not a comment\";", "String s = \"\";")).isFalse();
        assertThat(JavaTokens.sameCode("String s = \"import x;\";", "String s = \"\";")).isFalse();
        assertThat(JavaTokens.sameCode("String s = \"\"\"\n  a /* b */\n\"\"\";", "String s = \"\"\"\n  a\n\"\"\";"))
                .isFalse();
        assertThat(JavaTokens.sameCode("char c = '\\'';", "char c = '\\'' ;")).isTrue();
    }
}
