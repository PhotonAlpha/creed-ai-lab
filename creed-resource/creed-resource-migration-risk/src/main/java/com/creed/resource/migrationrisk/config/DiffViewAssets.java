package com.creed.resource.migrationrisk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves RefactoringMiner's own viewer scripts and styles ({@code web/dist/*} inside its jar) for the diff
 * page, so decorations, folding and click-to-jump between mapped code behave exactly as in its web diff.
 */
@Configuration(proxyBeanMethods = false)
public class DiffViewAssets implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/migration-risk/view/assets/**")
                .addResourceLocations("classpath:/web/dist/");
    }
}
