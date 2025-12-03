package com.cascade.smppmls;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class SmppMlsApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kabul"));
        SpringApplication.run(SmppMlsApplication.class, args);
    }
}
