FROM eclipse-temurin:21.0.12_8-jre
WORKDIR /app
COPY --chown=10001:10001 target/dlc-billing-api-0.1.0-SNAPSHOT.jar /app/billing.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/billing.jar"]
