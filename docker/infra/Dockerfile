# Scheduler-infra container. Built from :standalone-runner shadow JAR.
# Java 21 LTS — matches jvmToolchain in buildSrc convention plugins.

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY standalone-runner-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
