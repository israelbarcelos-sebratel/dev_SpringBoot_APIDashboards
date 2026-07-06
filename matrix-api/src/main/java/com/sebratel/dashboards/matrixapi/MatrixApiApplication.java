package com.sebratel.dashboards.matrixapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sebratel.dashboards")
public class MatrixApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatrixApiApplication.class, args);
    }
}
