package com.xinjian.capsulecode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.xinjian.capsulecode.mapper")
public class CapsuleCodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(CapsuleCodeApplication.class, args);
    }
}
