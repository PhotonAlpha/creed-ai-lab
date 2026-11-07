package com.creed.parent.controller;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.controller.vo.BatchFormVo;
import com.creed.parent.controller.vo.RouteFormVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import com.creed.parent.service.ReportGenerationService;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/2/24
 */
@RestController
@RequestMapping("/api/system/report")
@Slf4j
public class ReportRestController {
    @Resource
    private ReportGenerationService reportGenerationService;
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;

    @PostMapping("/generate-report")
    public HttpEntity<byte[]> saveCommit(@RequestBody RouteFormVo routeForm) throws IOException {
        log.info("routeInfos:{}", routeForm);
        Boolean compareAll = routeForm.getCompareAll();
        Pair<String, ByteArrayOutputStream> outputStreamPair;
        outputStreamPair = reportGenerationService.generateReport(routeForm);
        return outputFileDownload(outputStreamPair);
    }

    private static HttpEntity<byte[]> outputFileDownload(Pair<String, ByteArrayOutputStream> outputStreamPair) {
        ByteArrayOutputStream file = outputStreamPair.getRight();
        byte[] documentBody = file.toByteArray();
        HttpHeaders header = new HttpHeaders();
        header.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        header.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + outputStreamPair.getLeft());
        header.setContentLength(documentBody.length);

        return new HttpEntity<>(documentBody, header);
    }
    private static HttpEntity<byte[]> outputFileDownload(Triple<String, ByteArrayOutputStream, List<RouteInfoVo>> outputStreamPair) {
        ByteArrayOutputStream file = outputStreamPair.getMiddle();
        byte[] documentBody = file.toByteArray();
        HttpHeaders header = new HttpHeaders();
        header.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        header.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + outputStreamPair.getLeft());
        header.setContentLength(documentBody.length);

        return new HttpEntity<>(documentBody, header);
    }

    @PostMapping("/generate-report-batch")
    public HttpEntity<byte[]> generateReportBatch(@RequestBody BatchFormVo routeForm) throws IOException {
        log.info("routeInfos:{}", routeForm);
        Pair<String, ByteArrayOutputStream> outputStreamPair = reportGenerationService.generateReportBatch(routeForm);
        return outputFileDownload(outputStreamPair);
    }

    @PostMapping("/generate-pdf-batch")
    public void generatePdfBatch(@RequestBody BatchFormVo routeForm, HttpServletResponse response) throws IOException {
        log.info("generatePdfBatch:{}", JacksonUtils.toJsonString(routeForm));
        reportGenerationService.generatePdfBatch(routeForm, new Locale("en"), response);
    }
}
