package com.creed.gatewayproxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code creed.splunk.targets[].block}: absent inherits {@code creed.splunk.block}, empty windows is "never". */
class SplunkTargetBlockBindingTest {

    private static SplunkProperties bind(Map<String, String> extra) {
        Map<String, String> props = new java.util.HashMap<>(Map.of(
                "creed.splunk.block.windows", "22:00-09:00",
                "creed.splunk.block.zone", "Asia/Shanghai",
                "creed.splunk.targets[0].id", "UAT",
                "creed.splunk.targets[1].id", "SIT",
                "creed.splunk.targets[2].id", "DEV"));
        props.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(props)).bind("creed.splunk", SplunkProperties.class).get();
    }

    @Test
    void aTargetOverridesOnlyWhatItSets() {
        SplunkProperties splunk = bind(Map.of(
                "creed.splunk.targets[1].block.windows", "00:00-07:00",
                "creed.splunk.targets[2].block.windows", "",
                "creed.splunk.targets[2].block.zone", "UTC"));

        assertThat(splunk.targets().get(0).block()).isNull();
        assertThat(splunk.blockOf(splunk.targets().get(0))).isEqualTo(new SplunkProperties.Block("22:00-09:00", "Asia/Shanghai"));
        assertThat(splunk.blockOf(splunk.targets().get(1))).isEqualTo(new SplunkProperties.Block("00:00-07:00", "Asia/Shanghai"));
        assertThat(splunk.blockOf(splunk.targets().get(2))).as("empty windows: never blocked, not inherited")
                .isEqualTo(new SplunkProperties.Block("", "UTC"));
    }

    @Test
    void aTyposInATargetsWindowsFailsStartup() {
        assertThatThrownBy(() -> bind(Map.of("creed.splunk.targets[1].block.windows", "7am-9am")))
                .hasRootCauseMessage("creed.splunk.block.windows: '7am-9am' is neither HH:mm-HH:mm nor yyyy-MM-dd HH:mm[:ss] - yyyy-MM-dd HH:mm[:ss]");
    }
}
