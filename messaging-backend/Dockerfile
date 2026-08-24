FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Sized for Render's free 512 MB container, and tuned for startup rather than peak throughput,
# because on a free instance startup is the thing users actually experience.
#
#   TieredStopAtLevel=1  compile with C1 only. Full tiered compilation spends real time during boot
#                        producing C2-optimised code for methods a booting Spring context calls a
#                        handful of times and then never again. C1 compiles far more cheaply and
#                        emits smaller code, which also eases the 32 MB code cache below. The cost
#                        is lower peak throughput once warm - irrelevant at this traffic, where the
#                        server is idle most of the time and boots more often than it saturates.
#   UseSerialGC          the JVM already picks this for a container under ~1.8 GB; stating it
#                        removes the doubt and lets the two G1-shaped thread flags that used to be
#                        here go away, since a serial collector has no thread pools to size.
ENTRYPOINT ["java", "-Xms64m", "-Xmx192m", "-XX:MaxMetaspaceSize=112m", "-XX:ReservedCodeCacheSize=32m", "-XX:CompressedClassSpaceSize=32m", "-Xss384k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=2", "-jar", "app.jar"]