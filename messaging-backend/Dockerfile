FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms64m", "-Xmx180m", "-XX:MaxMetaspaceSize=96m", "-XX:ReservedCodeCacheSize=32m", "-Xss256k", "-jar", "app.jar"]