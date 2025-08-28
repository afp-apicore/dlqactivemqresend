package com.afp.searchserver.dlqactivemqresend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class DlqActivemqResendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlqActivemqResendApplication.class, args);
        browse();
    }

    public static void browse() {
        DLQMessageProcessor processor = new DLQMessageProcessor();
        try {
            processor.connect();

            // First, browse messages to see what's in the DLQ
//            processor.browseDLQMessages();

            // Then process and resend messages
            processor.processDLQMessages();

        } catch (Exception e) {
            log.error("Error processing DLQ messages", e);
        } finally {
            processor.disconnect();
        }
    }

}
