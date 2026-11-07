package com.creed.parent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author EthanCao
 * @description Default (Template) Project
 * @date 8/26/24
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.creed.parent")
@EnableJpaRepositories(basePackages = {
        "com.creed.parent.dal.repository"
})
@EntityScan(basePackages = {
        "com.creed.parent.dal.entity"
})
@EnableScheduling
public class CCRGApplication {
    public static SpringApplicationBuilder parentBuilder;
    public static ConfigurableApplicationContext context;

    public static void main(String[] args) {
        SpringApplicationBuilder parentBuilder =
                new SpringApplicationBuilder(CCRGApplication.class);
        // parentBuilder.web(WebApplicationType.NONE);
        CCRGApplication.parentBuilder = parentBuilder;
        parentBuilder.properties("spring.profiles.active=local,matrix,settings");
        context = parentBuilder.run(args);
    }

}