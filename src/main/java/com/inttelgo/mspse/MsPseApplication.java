package com.inttelgo.mspse;

import com.inttelgo.mspse.config.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class MsPseApplication {

    public static void main(String[] args) {
        DotEnvLoader.load();
        SpringApplication.run(MsPseApplication.class, args);
    }
}
