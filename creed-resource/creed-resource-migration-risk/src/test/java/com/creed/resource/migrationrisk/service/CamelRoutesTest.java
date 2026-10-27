package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CamelRoutesTest {

    @Test
    void routesAreKeyedByIdAndNormalized() {
        var parsed = CamelRoutes.parse("""
                <camelContext xmlns="http://camel.apache.org/schema/spring" id="ctx">
                  <!-- a comment -->
                  <route id="a" autoStartup="false">
                    <from uri="direct:a"/>
                    <filter>
                      <simple>
                        ${body} != null
                      </simple>
                      <to id="t1" uri="mock:x"/>
                    </filter>
                  </route>
                  <route><from uri="timer:tick"/></route>
                  <restConfiguration component="servlet"/>
                </camelContext>
                """);
        assertThat(parsed.routes()).containsOnlyKeys("a", "from timer:tick");
        assertThat(parsed.routes().get("a").steps()).containsExactly(
                "route autoStartup=false",
                "from uri=direct:a",
                "filter",
                "  simple: ${body} != null",
                "  to id=t1 uri=mock:x");
        assertThat(parsed.rest()).containsExactly("camelContext id=ctx", "  restConfiguration component=servlet");
    }

    @Test
    void prefixesAttributeOrderAndFormattingAreNotDifferences() {
        var plain = CamelRoutes.parse("<routes><route id=\"r\"><to uri=\"x\" pattern=\"InOnly\"/></route></routes>");
        var prefixed = CamelRoutes.parse("""
                <camel:routes xmlns:camel="http://camel.apache.org/schema/spring">
                  <camel:route id="r">
                    <camel:to   pattern="InOnly"   uri="x" />
                  </camel:route>
                </camel:routes>
                """);
        assertThat(prefixed.routes().get("r")).isEqualTo(plain.routes().get("r"));
    }

    @Test
    void notCamelOrNotXml() {
        assertThat(CamelRoutes.parse("<beans><bean id=\"route\"/></beans>").routes()).isEmpty();
        assertThat(CamelRoutes.mayContainRoutes("<project/>")).isFalse();
        assertThatThrownBy(() -> CamelRoutes.parse("<routes><route>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CamelRoutes.parse("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><routes>&x;</routes>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepDiffReadsLikeGitDiffRepo1Repo2() {
        assertThat(CamelRouteAnalyzer.diff(java.util.List.of("from uri=a", "to uri=b"), java.util.List.of("from uri=a", "to uri=c")))
                .containsExactly("- to uri=b", "+ to uri=c");
    }
}
