package com.afp.searchserver.dlqactivemqresend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class DLQMessageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DLQMessageProcessor.class);
    private static final String DLQ_NAME = "ActiveMQ.DLQ";

    static {
        StreamReadConstraints.overrideDefaultStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(5000)
                        .build()
        );
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String activeMQurl;
    private final String password;
    private final String username;

    private Connection connection;
    private Session session;
    private int processedCount = 0;
    private int failedCount = 0;

    public DLQMessageProcessor(@Value("${activemq.url}") String activeMQurl,
                               @Value("${activemq.username}") String username,
                               @Value("${activemq.password}") String password) {
        this.activeMQurl = activeMQurl;
        this.username = username;
        this.password = password;
    }

    public void handleDeadLetterQueue() {
        try {
            connect();

            // Process and resend messages from DLQ
            processDLQMessages();

        } catch (JMSException e) {
            logger.error("JMS error during scheduled DLQ processing", e);
        } catch (Exception e) {
            logger.error("Unexpected error during scheduled DLQ processing", e);
        } finally {
            disconnect();
        }
    }

    public void connect() throws JMSException {
        logger.info("Connecting to ActiveMQ at {}", activeMQurl);

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(activeMQurl);
        connectionFactory.setUserName(username);
        connectionFactory.setPassword(password);

        connection = connectionFactory.createConnection();
        connection.start();

        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        logger.info("Connected successfully to ActiveMQ");
    }

    private String getDlqDeliveryFailureCause(Message message) throws JMSException {
        return message.getStringProperty("dlqDeliveryFailureCause");
    }

    public void processDLQMessages() throws JMSException {
        logger.info("Processing and resending messages from DLQ");

        Queue dlqQueue = session.createQueue(DLQ_NAME);
        MessageConsumer consumer = session.createConsumer(dlqQueue);
        MessageProducer producer = session.createProducer(null);

        try {
            Message message;
            while ((message = consumer.receive(1000)) != null) { // 1 second timeout
                try {
                    String dlqDeliveryFailureCause = getDlqDeliveryFailureCause(message);
                    if (dlqDeliveryFailureCause != null) {
                        logger.info("DlqDeliveryFailureCause: {}", dlqDeliveryFailureCause);
                        if (dlqDeliveryFailureCause.contains("duplicate")) {
                            //ignore message
                            continue;
                        }
                    }

                    String originalDestination = getOriginalDestination(message);

                    if (originalDestination != null) {
                        resendMessage(message, originalDestination, producer);
                        processedCount++;
                        logger.info("Successfully resent message {} to {}",
                                message.getJMSMessageID(), originalDestination);
                    } else {
                        logger.warn("Could not determine original destination for message {}",
                                message.getJMSMessageID());
                        failedCount++;
                    }

                } catch (Exception e) {
                    logger.error("Failed to process message {}: {}",
                            message.getJMSMessageID(), e.getMessage());
                    failedCount++;
                }
            }

        } finally {
            consumer.close();
            producer.close();

            logger.info("Processing complete. Processed: {}, Failed: {}",
                    processedCount, failedCount);
        }
    }

    private String getOriginalDestination(Message message) throws JMSException {
        if (!(message instanceof TextMessage textMessage)) {
            logger.warn("Unexpected Message Type: {}", message.getClass().getSimpleName());
            return null;
        }

        String text = extractAndLogTextContent(textMessage);
        if (text == null) {
            return null;
        }

        String jsonText = getJsonText(text);
        return determineDestinationFromJson(jsonText);
    }

    private String extractAndLogTextContent(TextMessage textMessage) throws JMSException {
        String text = textMessage.getText();
        logger.info("Text Content: {}", text != null && text.length() > 200 ?
                text.substring(0, 200) + "..." : text);
        return text;
    }

    private String getJsonText(String text) {
        if (text == null || !text.startsWith("\"") || !text.endsWith("\"") || text.length() <= 2) {
            return text;
        }

        try {
            return objectMapper.readValue(text, String.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to get json string {}", e.getMessage());
            return null;
        }
    }

    private String determineDestinationFromJson(String jsonText) {
        if (jsonText == null || !jsonText.trim().startsWith("{")) {
            return null;
        }

        try {
            Object jsonObject = objectMapper.readValue(jsonText, Object.class);
            if (jsonObject instanceof LinkedHashMap<?, ?> map) {
                @SuppressWarnings("unchecked")
                LinkedHashMap<String, Object> typedMap = (LinkedHashMap<String, Object>) map;

                if (typedMap.containsKey("records")) {
                    return "topic://statistics.links";
                } else if (typedMap.containsKey("queries")) {
                    return "topic://statistics.queries";
                } else if (typedMap.containsKey("command")) {
                    return getCommandBasedDestination(typedMap);
                } else if (typedMap.containsKey("rendition")) {
                    return "topic://statistics.stegano";
                }
            } else if (jsonObject instanceof List<?>) {
                return "topic://notify";
            }
        } catch (Exception e) {
            logger.debug("Message content is not valid JSON: {}", e.getMessage());
        }

        return null;
    }

    private String getCommandBasedDestination(LinkedHashMap<String, Object> map) {
        Object command = map.get("command");
        Object name = map.get("name");

        if (command instanceof String commandStr) {
            if ("UPDATE".equals(commandStr) || ("DELETE".equals(commandStr) && "user".equals(name))) {
                return "topic://virtualCache";
            }
        }

        return "topic://cacheTopic";
    }

    private void resendMessage(Message originalMessage, String destinationName,
                               MessageProducer producer) throws JMSException {

        // Create the destination
        Destination destination;
        if (destinationName.startsWith("queue://")) {
            destination = session.createQueue(destinationName.substring(8));
        } else if (destinationName.startsWith("topic://")) {
            destination = session.createTopic(destinationName.substring(8));
        } else {
            // Assume it's a queue if no prefix
            destination = session.createQueue(destinationName);
        }

        // Create a new message based on the original
        Message newMessage = createNewMessage(originalMessage);

        // Clean up DLQ-specific properties
        cleanDLQProperties(newMessage);

        // Send the message
        producer.send(destination, newMessage);

        logger.debug("Message resent to destination: {}", destinationName);
    }

    private Message createNewMessage(Message originalMessage) throws JMSException {
        Message newMessage;

        // Handle different message types
        if (originalMessage instanceof TextMessage) {
            TextMessage originalText = (TextMessage) originalMessage;
            TextMessage newText = session.createTextMessage(originalText.getText());
            newMessage = newText;

        } else if (originalMessage instanceof BytesMessage) {
            BytesMessage originalBytes = (BytesMessage) originalMessage;
            BytesMessage newBytes = session.createBytesMessage();

            // Copy bytes
            originalBytes.reset();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = originalBytes.readBytes(buffer)) > 0) {
                newBytes.writeBytes(buffer, 0, length);
            }
            newMessage = newBytes;

        } else if (originalMessage instanceof ObjectMessage) {
            ObjectMessage originalObject = (ObjectMessage) originalMessage;
            ObjectMessage newObject = session.createObjectMessage(originalObject.getObject());
            newMessage = newObject;

        } else if (originalMessage instanceof MapMessage) {
            MapMessage originalMap = (MapMessage) originalMessage;
            MapMessage newMap = session.createMapMessage();

            @SuppressWarnings("unchecked")
            Enumeration<String> mapNames = originalMap.getMapNames();
            while (mapNames.hasMoreElements()) {
                String name = mapNames.nextElement();
                newMap.setObject(name, originalMap.getObject(name));
            }
            newMessage = newMap;

        } else {
            // For other types, create a generic message
            newMessage = session.createMessage();
        }

        // Copy properties (excluding JMS and DLQ specific ones)
        copyMessageProperties(originalMessage, newMessage);

        return newMessage;
    }

    private void copyMessageProperties(Message source, Message target) throws JMSException {
        @SuppressWarnings("unchecked")
        Enumeration<String> propertyNames = source.getPropertyNames();

        while (propertyNames.hasMoreElements()) {
            String propertyName = propertyNames.nextElement();

            // Skip JMS standard headers and DLQ specific properties
            if (!propertyName.startsWith("JMS") &&
                    !isDLQSpecificProperty(propertyName)) {

                try {
                    Object value = source.getObjectProperty(propertyName);
                    target.setObjectProperty(propertyName, value);
                } catch (Exception e) {
                    logger.debug("Could not copy property {}: {}", propertyName, e.getMessage());
                }
            }
        }
    }

    private boolean isDLQSpecificProperty(String propertyName) {
        String[] dlqProperties = {
                "dlqDeliveryFailureCause",
                "originalDestination",
                "AMQ_ORIGINAL_DESTINATION",
                "breadcrumbId",
                "AMQ_ORIG_DESTINATION"
        };

        for (String dlqProp : dlqProperties) {
            if (propertyName.equals(dlqProp)) {
                return true;
            }
        }

        return false;
    }

    private void cleanDLQProperties(Message message) {
        // Remove DLQ-specific properties that shouldn't be on the resent message
        String[] propertiesToRemove = {
                "dlqDeliveryFailureCause",
                "breadcrumbId",
                "AMQ_ORIG_DESTINATION"
        };

        for (String property : propertiesToRemove) {
            try {
                // JMS doesn't have a direct remove property method,
                // but setting to null effectively removes it in most implementations
                message.setObjectProperty(property, null);
            } catch (Exception e) {
                logger.debug("Could not remove property {}: {}", property, e.getMessage());
            }
        }
    }

    public void disconnect() {
        try {
            if (session != null) {
                session.close();
            }
            if (connection != null) {
                connection.close();
            }
            logger.info("Disconnected from ActiveMQ");
        } catch (JMSException e) {
            logger.error("Error closing ActiveMQ connection", e);
        }
    }

    // Utility method for processing specific messages
    void processSpecificMessage(String messageId) throws JMSException {
        String messageSelector = "JMSMessageID = '" + messageId + "'";

        Queue dlqQueue = session.createQueue(DLQ_NAME);
        MessageConsumer consumer = session.createConsumer(dlqQueue, messageSelector);
        MessageProducer producer = session.createProducer(null);

        try {
            Message message = consumer.receive(5000); // 5 second timeout
            if (message != null) {
                String originalDestination = getOriginalDestination(message);
                if (originalDestination != null) {
                    resendMessage(message, originalDestination, producer);
                    logger.info("Specific message {} resent to {}", messageId, originalDestination);
                } else {
                    logger.warn("Could not determine original destination for message {}", messageId);
                }
            } else {
                logger.warn("Message with ID {} not found in DLQ", messageId);
            }
        } finally {
            consumer.close();
            producer.close();
        }
    }


    void browseDLQMessages() throws JMSException {
        logger.info("Browsing messages in DLQ: {}", DLQ_NAME);

        Queue dlqQueue = session.createQueue(DLQ_NAME);
        QueueBrowser browser = session.createBrowser(dlqQueue);

        @SuppressWarnings("unchecked")
        Enumeration<Message> messages = browser.getEnumeration();

        int messageCount = 0;
        while (messages.hasMoreElements()) {
            Message message = messages.nextElement();
            messageCount++;

            logger.info("=== Message {} ===", messageCount);
            if (!(message instanceof TextMessage textMessage)) {
                logger.warn("Unexpected Message Type: {}", message.getClass().getSimpleName());
                continue;
            }

            String dlqDeliveryFailureCause = getDlqDeliveryFailureCause(message);
            if (dlqDeliveryFailureCause != null) {
                logger.info("DlqDeliveryFailureCause: {}", dlqDeliveryFailureCause);
            }

            String text = textMessage.getText();
            logger.info("Text Content: {}", text != null && text.length() > 100 ?
                    text.replaceAll("\n", "") : text);
        }

        browser.close();
        logger.info("Found {} messages in DLQ", messageCount);
    }
}