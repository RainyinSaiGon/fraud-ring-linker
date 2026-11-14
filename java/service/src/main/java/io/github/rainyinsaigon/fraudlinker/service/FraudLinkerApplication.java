package io.github.rainyinsaigon.fraudlinker.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudLinkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudLinkerApplication.class, args);
    }

    /** One clock for every timestamp the service writes, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
