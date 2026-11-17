package com.smartblock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SmartBlockApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartBlockApplication.class, args);
        System.out.println("\n  SmartBlock is running -> http://localhost:8080\n");
    }
}
