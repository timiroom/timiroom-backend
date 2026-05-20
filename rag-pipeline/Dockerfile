# Multi-stage build for RAG Pipeline
FROM gradle:8.8-jdk21 AS builder

WORKDIR /app

COPY build.gradle settings.gradle gradle.properties* ./
COPY gradle ./gradle
COPY src ./src

RUN gradle build -x test --no-daemon

# Production stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-Xmx1536m -Xms512m"

COPY --from=builder /app/build/libs/*.jar app.jar

RUN addgroup -g 1000 spring && \
    adduser -u 1000 -G spring -s /bin/sh -D spring

USER spring

EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
