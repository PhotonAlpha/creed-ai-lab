package com.creed.jasper;

import com.creed.jasper.api.ApprovalStatusJasperController;
import com.creed.jasper.service.JasperFonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context starts and wires the renderer — no web server, no ports, nothing external. Cheap
 * insurance for the two things a unit test cannot see: the {@code @Value} on
 * {@code creed.jasper.cache-templates}, and {@link JasperFonts}'s startup check, which is what
 * puts the resolved faces in the log where a wrong one is visible before anyone downloads a PDF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreedJasperReportApplicationTest {

    @Autowired
    private ApprovalStatusJasperController controller;

    @Autowired
    private JasperFonts fonts;

    @Test
    void theRendererIsWired() {
        assertThat(controller).isNotNull();
        assertThat(fonts.resolvedFaces()).containsKeys("en", "th", "zh-CN", "zh-TW", "ms", "vi");
    }
}
