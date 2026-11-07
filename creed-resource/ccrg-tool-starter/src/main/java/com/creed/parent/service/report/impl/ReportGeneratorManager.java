package com.creed.parent.service.report.impl;

import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.service.report.ReportGenerator;
import com.creed.parent.service.report.ReportMetadataInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T18
 */
@Service
@Primary
@Slf4j
public class ReportGeneratorManager implements ReportGenerator {
    @Resource
    @Lazy
    List<ReportGenerator> reportGeneratorProviders;

    @Override
    public void generateReport(List<EnvironmentConfigurationVo> environmentConfiguration, ReportTypeEnum reportType, Locale locale, ReportMetadataInfo metadata, OutputStream os) {
        for (ReportGenerator provider : reportGeneratorProviders) {
            if (provider.supports(reportType)) {
                provider.generateReport(environmentConfiguration, reportType, locale, metadata, os);
                return;
            }
        }
        throw new ServiceException(500, "Unsupported reportType " + reportType);
    }

    @Override
    public boolean supports(ReportTypeEnum reportType) {
        // always return false
        return false;
    }
}
