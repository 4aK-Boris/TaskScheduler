# Demo :app container. Same Java target as scheduler-infra.
# In real usage you'd bundle your own application code here.

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY app-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
