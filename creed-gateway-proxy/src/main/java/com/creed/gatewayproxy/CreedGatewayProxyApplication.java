package com.creed.gatewayproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.creed.gatewayproxy.service.splunk.EncryptCommand;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedGatewayProxyApplication {

    public static void main(String[] args) {
        // `java -jar creed-gateway-proxy.jar encrypt`: produce an ENC(…) password, no application context.
        if (args.length > 0 && "encrypt".equals(args[0])) {
            System.exit(EncryptCommand.run());
        }
        SpringApplication.run(CreedGatewayProxyApplication.class, args);
    }
}
