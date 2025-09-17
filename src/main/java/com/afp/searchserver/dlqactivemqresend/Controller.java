package com.afp.searchserver.dlqactivemqresend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @Autowired
    private DLQMessageProcessor dlqMessageProcessor;

    @GetMapping("/resend")
    public void resend() {
        dlqMessageProcessor.handleDeadLetterQueue();
    }
}
