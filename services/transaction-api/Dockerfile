FROM eclipse-temurin:21-jre

WORKDIR /transaction-api

COPY target/*.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]