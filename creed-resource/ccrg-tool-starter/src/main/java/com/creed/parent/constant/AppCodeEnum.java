package com.creed.parent.constant;

import lombok.Getter;
import org.apache.commons.lang3.Strings;

import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 27/5/25
 */
public enum AppCodeEnum {
    MS("LX", "MS"),
    IDBSG_FDR_INT("IDBSG_FDR_INT", "MS"),
    IDBSG_FDR_OPS("IDBSG_FDR_OPS", "MS"),
    LX_APP(Constants.LX_APP, "CCS"),

    CCS(Constants.LX_APP, "CCS"),
    CCS_AGGREGATOR(Constants.LX_APP, "CCS_AGGREGATOR"),
    CCS_LC(Constants.LX_APP, "CCS_LC"),
    CCS_LC_AGGREGATOR(Constants.LX_APP, "CCS_LC_AGGREGATOR")
    ;
    @Getter
    private String fileName;
    @Getter
    private String appNature;

    AppCodeEnum(String fileName, String appNature) {
        this.fileName = fileName;
        this.appNature = appNature;
    }

    public static String toDisplayName(String name, Predicate<AppCodeEnum> appCodePredicate) {
        return Stream.of(AppCodeEnum.values())
                .filter(appCodePredicate)
                .filter(e -> Strings.CI.equals(e.getFileName(), name))
                .map(AppCodeEnum::name)
                .findFirst().orElse("");
    }
    public static String toDisplayName(String name) {
        return toDisplayName(name, appCode -> true);
    }
    public static String toFileName(String displayName){
        return Stream.of(AppCodeEnum.values()).filter(e -> Strings.CI.startsWith(displayName, e.name()))
                .map(AppCodeEnum::getFileName)
                .findFirst().orElse("");
    }
    public static AppCodeEnum toAppEnum(String displayName){
        return Stream.of(AppCodeEnum.values()).filter(e -> Strings.CI.startsWith(displayName, e.name()))
                .findFirst().orElse(null);
    }

    private static class Constants {
        public static final String LX_APP = "LX_APP";
    }
}
