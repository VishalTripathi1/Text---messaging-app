FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms64m", "-Xmx192m", "-XX:MaxMetaspaceSize=112m", "-XX:ReservedCodeCacheSize=32m", "-XX:CompressedClassSpaceSize=32m", "-Xss384k", "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1", "-XX:CICompilerCount=2", "-jar", "app.jar"]