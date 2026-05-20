package com.creed.report.service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class AssetService {

    private static final String BOOTSTRAP_VERSION = "5.3.3";
    private static final String CSS_PATH = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/css/bootstrap.min.css";
    private static final String JS_PATH  = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/js/bootstrap.bundle.min.js";

    private String bootstrapCss;
    private String bootstrapJs;

    @PostConstruct
    void load() throws IOException {
        this.bootstrapCss = readClasspath(CSS_PATH).replace("</style>", "<\\/style>");
        this.bootstrapJs  = readClasspath(JS_PATH).replace("</script>", "<\\/script>");
    }

    public String bootstrapCss() {
        return bootstrapCss;
    }

    public String bootstrapJs() {
        return bootstrapJs;
    }

    private static String readClasspath(String location) throws IOException {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }
}
