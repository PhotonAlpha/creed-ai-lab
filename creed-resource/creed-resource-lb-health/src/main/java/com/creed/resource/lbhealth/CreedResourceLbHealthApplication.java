package com.creed.resource.lbhealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedResourceLbHealthApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedResourceLbHealthApplication.class, args);
    }
}
