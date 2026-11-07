package com.creed.parent.constant;

import com.creed.parent.exception.ServiceException;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T18
 */
public enum ReportTypeEnum {
    PDF("pdf"),XLSX("xlsx"),HTML("html");
    @Getter
    private String suffix;

    ReportTypeEnum(String suffix) {
        this.suffix = suffix;
    }

    public static ReportTypeEnum findByType(String reportType) {
        return Stream.of(ReportTypeEnum.values())
                .filter(elem -> StringUtils.equalsIgnoreCase(elem.name(), reportType))
                .findFirst()
                .orElseThrow(() -> new ServiceException(500, "Unsupported report type " + reportType));
    }
}
