package com.creed.parent.service.report;

import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T10
 */
public interface ReportGenerator {
    void generateReport(List<EnvironmentConfigurationVo> environmentConfiguration, ReportTypeEnum reportType, Locale locale, ReportMetadataInfo metadata, OutputStream os);

    boolean supports(ReportTypeEnum reportType);

    default String generateFormattedFileName(List<RouteInfoVo> routeInfos, String suffix) {
        StringBuilder sb = new StringBuilder();
        Optional<RouteInfoVo> optionalRouteInfo = routeInfos.stream().findAny();
        String envConjunction = routeInfos.stream().map(RouteInfoVo::getEnvironment)
                .distinct().collect(Collectors.joining("_"));
        String insConjunction = routeInfos.stream().map(RouteInfoVo::getInstance)
                .distinct().collect(Collectors.joining("_"));
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getProjectName, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getCountryCode, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getApp, sb::append);
        fileNameConsumer(optionalRouteInfo, any -> envConjunction, sb::append);
        fileNameConsumer(optionalRouteInfo, any -> insConjunction, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getModule, sb::append);
        String module = optionalRouteInfo.map(RouteInfoVo::getModule).orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(module)) {
            return module + "/" + sb + "report_%s.%s".formatted(RandomStringUtils.randomAlphabetic(5), suffix);
        }
        return sb + "report_%s.%s".formatted(RandomStringUtils.randomAlphabetic(5), suffix);
    }

    default void fileNameConsumer(Optional<RouteInfoVo> optionalRouteInfo, Function<RouteInfoVo, String> stringSupplier, Consumer<String> appendConsumer) {
        optionalRouteInfo.map(stringSupplier).map("%s_"::formatted).ifPresent(appendConsumer);
    }
}
