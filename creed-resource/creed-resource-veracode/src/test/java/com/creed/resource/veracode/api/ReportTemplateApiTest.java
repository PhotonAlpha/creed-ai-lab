package com.creed.resource.veracode.api;

import com.creed.resource.veracode.mock.MockJiraStore;
import com.creed.resource.veracode.service.JiraToken;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload a company workbook -> grid JSON -> bind cells -> export an analysis through it, over the sample report and
 * the Jira mock; the templates in the test's in-memory H2.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(VeracodeApiTest.ClientsOverMockMvc.class)
class ReportTemplateApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    MockJiraStore jira;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        jira.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header(JiraToken.HEADER, "pat-0000-abcd"))
                .build();
    }

    /**
     * Two sheets as a company template has them: a title, a project cell, a header, one data row and a footer under
     * it; SAST on its own sheet.
     */
    private static byte[] companyTemplate() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sca = wb.createSheet("SCA components");
            sca.createRow(0).createCell(0).setCellValue("Veracode SCA report");
            sca.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            Row project = sca.createRow(1);
            project.createCell(0).setCellValue("Project");
            Row header = sca.createRow(3);
            String[] titles = {"CVE", "File", "Severity", "Jira"};
            for (int c = 0; c < titles.length; c++) {
                header.createCell(c).setCellValue(titles[c]);
            }
            sca.createRow(4).createCell(0).setCellValue("(data)");
            sca.createRow(6).createCell(0).setCellValue("End of report");
            Sheet sast = wb.createSheet("SAST code flaws");
            Row sastHeader = sast.createRow(0);
            sastHeader.createCell(0).setCellValue("Jira summary");
            sastHeader.createCell(1).setCellValue("Flaw IDs");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "公司模版.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", companyTemplate());
        String body = mvc.perform(multipart("/api/veracode/report-templates").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("公司模版"))
                .andExpect(jsonPath("$.sheets.length()").value(2))
                .andExpect(jsonPath("$.sheets[0].name").value("SCA components"))
                .andExpect(jsonPath("$.sheets[0].data[0].cells[0].text").value("Veracode SCA report"))
                .andExpect(jsonPath("$.sheets[0].merges[0].lastCol").value(3))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static final String MAPPING = """
            {"sheets":[
              {"sheet":"SCA components",
               "lists":[{"dataset":"sca","row":4,"cells":[
                 {"col":0,"field":"cveId"},{"col":1,"field":"fileName"},{"col":2,"field":"severity"},
                 {"col":3,"field":"jiraKeys"},{"col":4,"field":"no"}]}],
               "values":[{"row":1,"col":1,"field":"projectKey"}]},
              {"sheet":"SAST code flaws",
               "lists":[{"dataset":"sast","row":1,"cells":[{"col":0,"field":"summary"},{"col":1,"field":"flawIds"}]}]}
            ]}""";

    @Test
    void aWorkbookBoundOnThePageIsFilledWithTheAnalysis() throws Exception {
        String id = upload();
        mvc.perform(put("/api/veracode/report-templates/" + id + "/mapping").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.sheets[0].lists[0].cells.length()").value(5));
        mvc.perform(get("/api/veracode/report-templates"))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].lists").value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].values").value(org.hamcrest.Matchers.contains(1)));

        // the JXLS template: markup, nothing filled
        byte[] markup = mvc.perform(get("/api/veracode/report-templates/" + id + "/jxls"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(markup))) {
            Sheet sca = wb.getSheet("SCA components");
            assertThat(sca.getRow(0).getCell(0).getCellComment().getString().getString())
                    .isEqualTo("jx:area(lastCell=\"E7\")");
            assertThat(sca.getRow(4).getCell(0).getCellComment().getString().getString())
                    .isEqualTo("jx:each(items=\"sca\" var=\"r\" lastCell=\"E5\")");
            assertThat(sca.getRow(4).getCell(0).getStringCellValue()).isEqualTo("${r.cveId}");
            assertThat(sca.getRow(1).getCell(1).getStringCellValue()).isEqualTo("${summary.projectKey}");
        }

        String uploadId = JsonPath.read(mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "detailedreport.xml", "application/xml",
                                new ClassPathResource(VeracodeController.SAMPLE).getContentAsByteArray()))
                        .param("jiraVersions", "2.3.0"))
                .andReturn().getResponse().getContentAsString(), "$.uploadId");
        var response = mvc.perform(get("/api/veracode/report-templates/" + id + "/export")
                        .param("uploadId", uploadId).param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sca = wb.getSheet("SCA components");
            // untouched cells stay, the summary cell is filled
            assertThat(sca.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Veracode SCA report");
            assertThat(sca.getRow(1).getCell(1).getStringCellValue()).isEqualTo("CREED");
            assertThat(sca.getRow(3).getCell(0).getStringCellValue()).isEqualTo("CVE");
            // ten findings from row 5 on; the footer moved down below them
            List<String> cves = new ArrayList<>();
            for (int r = 4; r < 14; r++) {
                cves.add(sca.getRow(r).getCell(0).getStringCellValue());
            }
            assertThat(cves).contains("CVE-2021-44228").doesNotContain("${r.cveId}");
            int log4shell = 4 + cves.indexOf("CVE-2021-44228");
            assertThat(sca.getRow(log4shell).getCell(1).getStringCellValue()).isEqualTo("log4j-core-2.14.1.jar");
            assertThat(sca.getRow(log4shell).getCell(3).getStringCellValue()).isEqualTo("CREED-101");
            assertThat(sca.getRow(15).getCell(0).getStringCellValue()).isEqualTo("End of report");
            // numbered from 1
            assertThat(sca.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(1);
            assertThat(sca.getRow(13).getCell(4).getNumericCellValue()).isEqualTo(10);

            Sheet sast = wb.getSheet("SAST code flaws");
            assertThat(sast.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Jira summary");
            assertThat(sast.getRow(1).getCell(0).getStringCellValue()).startsWith("creed-sast - CWE-");
            assertThat(sast.getRow(2).getCell(0).getStringCellValue()).startsWith("creed-sast - CWE-");
            assertThat(sast.getRow(1).getCell(1).getStringCellValue()).isNotBlank();
        }

        mvc.perform(delete("/api/veracode/report-templates/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/veracode/report-templates/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("template_not_found"));
    }

    @Test
    void whatJxlsCouldNotHonourIsRefusedBeforeSaving() throws Exception {
        String id = upload();
        // a summary cell on a data row would repeat
        mvc.perform(put("/api/veracode/report-templates/" + id + "/mapping").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sheets":[{"sheet":"SCA components",
                                  "lists":[{"dataset":"sca","row":4,"cells":[{"col":0,"field":"cveId"}]}],
                                  "values":[{"row":4,"col":2,"field":"projectKey"}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("C5: a summary field on a data row would repeat")));
        // an unknown field
        mvc.perform(put("/api/veracode/report-templates/" + id + "/mapping").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sheets":[{"sheet":"SCA components",
                                  "lists":[{"dataset":"sca","row":0,"cells":[{"col":0,"field":"nope"}]}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("no field 'nope' in sca")));
        mvc.perform(put("/api/veracode/report-templates/" + id + "/mapping").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheets\":[{\"sheet\":\"Missing\",\"values\":[{\"row\":0,\"col\":0,\"field\":\"projectKey\"}]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("no sheet 'Missing'")));
        // nothing bound yet: no export
        mvc.perform(get("/api/veracode/report-templates/" + id + "/export").param("uploadId", "x"))
                .andExpect(status().is4xxClientError());
        // not a workbook
        mvc.perform(multipart("/api/veracode/report-templates")
                        .file(new MockMultipartFile("file", "report.xlsx", "application/octet-stream", "not excel".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not an Excel workbook")));
    }
}
