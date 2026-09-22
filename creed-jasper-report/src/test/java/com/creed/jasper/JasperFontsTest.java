package com.creed.jasper;

import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.service.JasperFonts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The font extension: which embedded face each language actually resolves to, and the two ways
 * that can be wrong without anything failing.
 *
 * <p>Both are silent in production — a PDF comes back, it is just missing every glyph of the
 * script it was asked for — so they are pinned here rather than left to a reader noticing.
 */
class JasperFontsTest {

    private final JasperFonts fonts = new JasperFonts();

    @Test
    void theSharedNotoFacesAreActuallyPackaged() {
        // They are creed-report's, copied in by a shared resource directory (see the pom). A clone
        // whose creed-report/src/main/resources/fonts is empty builds perfectly and then renders
        // the whole document in Helvetica; this is the assertion that says so out loud.
        for (String face : new String[] { "NotoSans-Regular.ttf", "NotoSans-Bold.ttf",
                "NotoSansThai-Regular.ttf", "NotoSansThai-Bold.ttf",
                "NotoSansSC-Regular.ttf", "NotoSansSC-Bold.ttf",
                "NotoSansTC-Regular.ttf", "NotoSansTC-Bold.ttf" }) {
            assertThat(new ClassPathResource("fonts/" + face).exists()).as(face).isTrue();
        }
    }

    @Test
    void eachLanguageResolvesToTheFaceThatCoversItsScript() {
        // The face's own name out of the TTF, so this also proves the file parsed.
        Map<String, String> faces = fonts.resolvedFaces();

        assertThat(faces).containsEntry("th", "Noto Sans Thai Regular");
        assertThat(faces).containsEntry("zh-CN", "Noto Sans SC Regular");
        // Not the SC face: collapsing Traditional onto Simplified is the mistake that makes a
        // Taiwanese document quietly wrong rather than visibly broken.
        assertThat(faces).containsEntry("zh-TW", "Noto Sans TC Regular");
        // The three that add no script. This is also the assertion that would fail if the
        // unrestricted family were declared FIRST in creed-fonts.xml -- it supports every locale,
        // so declared first it wins every lookup and Thai and CJK silently become Noto Sans.
        assertThat(faces).containsEntry("en", "Noto Sans Regular");
        assertThat(faces).containsEntry("ms", "Noto Sans Regular");
        assertThat(faces).containsEntry("vi", "Noto Sans Regular");

        assertThat(faces).hasSize(ReportLanguage.values().length);
    }
}
