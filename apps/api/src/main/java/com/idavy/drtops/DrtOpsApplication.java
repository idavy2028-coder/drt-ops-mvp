package com.idavy.drtops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DrtOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrtOpsApplication.class, args);
    }
}
