package com.afp.searchserver.dlqactivemqresend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class DlqActivemqResendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlqActivemqResendApplication.class, args);
    }

}
