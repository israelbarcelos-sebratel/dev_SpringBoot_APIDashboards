package com.sebratel.dashboards.nativeapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sebratel.dashboards")
public class NativeApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(NativeApiApplication.class, args);
    }
}
