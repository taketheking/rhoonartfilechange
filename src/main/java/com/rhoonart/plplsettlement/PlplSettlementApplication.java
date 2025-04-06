package com.rhoonart.plplsettlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
public class PlplSettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlplSettlementApplication.class, args);
    }

}
