# Use OpenJDK 17 as base image
FROM eclipse-temurin:17-jdk

# Set working directory
WORKDIR /app

# Copy Maven wrapper and pom.xml first for better layer caching
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn .mvn
COPY pom.xml .

# Make Maven wrapper executable
RUN chmod +x ./mvnw

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests

# Create a new stage for the runtime image
FROM eclipse-temurin:17-jre

# Set working directory
WORKDIR /app

# Copy the built JAR file from the previous stage
COPY --from=0 /app/target/dlq-activemq-resend-1.0.0.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs

# Environment variables with default values
ENV ACTIVEMQ_URL=tcp://localhost:61616
ENV ACTIVEMQ_USERNAME=admin
ENV ACTIVEMQ_PASSWORD=admin
ENV DLQ_CRON="0 0 7 * * *"

EXPOSE 10080

# Run the application
#ENTRYPOINT ["java", "-jar", "app.jar"]
CMD [ "sh", "java -jar app.jar" ]
