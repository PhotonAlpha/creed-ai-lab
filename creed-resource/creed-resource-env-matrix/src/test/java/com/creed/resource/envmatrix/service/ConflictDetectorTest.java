package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.ConflictGroup;
import com.creed.resource.envmatrix.api.dto.ConflictScope;
import com.creed.resource.envmatrix.domain.EnvEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the conflict semantics — the part of the tool that has to be right, since a false
 * negative here means a port clash ships and a false positive means the highlighting gets ignored.
 */
class ConflictDetectorTest {

    private static EnvEndpoint endpoint(long id, String tier, String env, String service,
                                       String scheme, String host, String ip, int port) {
        EnvEndpoint e = new EnvEndpoint();
        e.setId(id);
        e.setAppSystem("MS");
        e.setTier(tier);
        e.setEnvInstance(env);
        e.setCountry("CN");
        e.setService(service);
        e.setInstance("Green");
        e.setScheme(scheme);
        e.setHost(host);
        e.setIp(ip);
        e.setPort(port);
        return e;
    }

    @Test
    @DisplayName("two endpoints on the same host:port in one env instance are a HOST_PORT conflict")
    void detectsHostPortConflict() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER_ENV);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "UAT", "UAT1", "MS2", "https", "a.internal", "10.0.0.2", 8443)));

        assertThat(report.groups()).hasSize(1);
        ConflictDetector.RawGroup group = report.groups().getFirst();
        assertThat(group.kind()).isEqualTo(ConflictGroup.Kind.HOST_PORT);
        assertThat(group.scopeKey()).isEqualTo("UAT/UAT1");
        assertThat(group.value()).isEqualTo("a.internal:8443");
        assertThat(report.isConflicting(1L)).isTrue();
        assertThat(report.isConflicting(2L)).isTrue();
        assertThat(report.keysFor(1L)).containsExactly("host:port a.internal:8443");
    }

    @Test
    @DisplayName("two hostnames resolving to one ip:port are an IP_PORT conflict")
    void detectsIpPortConflict() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER_ENV);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.9", 8443),
                endpoint(2, "UAT", "UAT1", "MS2", "https", "b.internal", "10.0.0.9", 8443)));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().kind()).isEqualTo(ConflictGroup.Kind.IP_PORT);
        assertThat(report.groups().getFirst().value()).isEqualTo("10.0.0.9:8443");
    }

    @Test
    @DisplayName("http and https on the same host:port collide — one port cannot serve both")
    void detectsSchemeClashOnOnePort() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER_ENV);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "SIT", "SIT1", "MS4", "https", "a.internal", "10.0.0.1", 8403),
                endpoint(2, "SIT", "SIT1", "MS4", "http", "a.internal", "10.0.0.1", 8403)));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().kind()).isEqualTo(ConflictGroup.Kind.HOST_PORT);
    }

    @Test
    @DisplayName("identical host AND ip yields one group, labelled host:port rather than both")
    void dedupesOverlappingGroups() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER_ENV);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "UAT", "UAT1", "MS2", "https", "a.internal", "10.0.0.1", 8443)));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().kind()).isEqualTo(ConflictGroup.Kind.HOST_PORT);
        assertThat(report.keysFor(1L)).hasSize(1);
    }

    @Test
    @DisplayName("the same address in two env instances is not a conflict under TIER_ENV")
    void separateEnvironmentsMayReuseAnAddress() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER_ENV);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "UAT", "UAT2", "MS1", "https", "a.internal", "10.0.0.1", 8443)));

        assertThat(report.groups()).isEmpty();
        assertThat(report.isConflicting(1L)).isFalse();
    }

    @Test
    @DisplayName("widening the scope to TIER catches what TIER_ENV deliberately allows")
    void tierScopeCatchesCrossEnvironmentReuse() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.TIER);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "UAT", "UAT2", "MS1", "https", "a.internal", "10.0.0.1", 8443)));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().scopeKey()).isEqualTo("UAT");
    }

    @Test
    @DisplayName("GLOBAL scope compares across tiers too")
    void globalScopeIgnoresEnvironmentBoundaries() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.GLOBAL);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "PROD", "PROD1", "MS1", "https", "a.internal", "10.0.0.1", 8443)));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().scopeKey()).isEqualTo("*");
    }

    @Test
    @DisplayName("distinct addresses produce no conflicts")
    void cleanDataHasNoConflicts() {
        ConflictDetector detector = new ConflictDetector(ConflictScope.GLOBAL);

        ConflictDetector.Report report = detector.detect(List.of(
                endpoint(1, "UAT", "UAT1", "MS1", "https", "a.internal", "10.0.0.1", 8443),
                endpoint(2, "UAT", "UAT1", "MS2", "https", "b.internal", "10.0.0.2", 8444)));

        assertThat(report.groups()).isEmpty();
        assertThat(report.keysByEndpoint()).isEmpty();
    }
}
