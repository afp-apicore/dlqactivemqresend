package com.afp.searchserver.dlqactivemqresend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DLQSchedulerService implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(DLQSchedulerService.class);

    @Autowired
    private DLQMessageProcessor dlqMessageProcessor;

    /**
     * Scheduled task to process messages from the Dead Letter Queue (DLQ).
     * <p>
     * This method is triggered automatically based on the specified cron expression defined
     * in the application properties under the key `dlq.cron` and executes within the
     * `Europe/Paris` timezone. It logs the start and completion of the process,
     * delegating the actual processing to the `handleDeadLetterQueue` method of the
     * {@link DLQMessageProcessor}.
     * <p>
     * The primary purpose of this method is to ensure periodic handling of DLQ messages,
     * including connecting to the ActiveMQ server, processing messages, and resending
     * them to their original destinations. In case of any errors, detailed logs are generated
     * to aid troubleshooting.
     */
    @Scheduled(cron = "${dlq.cron}", zone = "Europe/Paris")
    public void processDLQMessages() {
        logger.info("Starting scheduled DLQ message processing...");
        dlqMessageProcessor.handleDeadLetterQueue();
        logger.info("Scheduled DLQ message processing completed");
    }

    /**
     * Invoked by the Spring framework after the bean properties have been set during the initialization phase.
     * <p>
     * This method ensures that any necessary processing of messages in the Dead Letter Queue (DLQ)
     * begins immediately after the bean has been fully initialized. It delegates the task to the
     * {@code processDLQMessages} method, which is responsible for connecting to the ActiveMQ server,
     * processing the messages, and resending them to their original destinations.
     * <p>
     * This method provides an initial trigger for the DLQ message processing mechanism before any
     * scheduled executions commence, ensuring that the system starts handling DLQ messages without delay
     * during application startup.
     *
     * @throws Exception if any error occurs during the DLQ message processing
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        logger.info("Starting DLQ message processing on start time ...");
        dlqMessageProcessor.handleDeadLetterQueue();
        logger.info("DLQ message processing on start time completed");
    }
}
