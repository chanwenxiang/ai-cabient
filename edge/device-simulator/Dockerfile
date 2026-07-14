FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl edge/device-simulator -am package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/edge/device-simulator/target/device-simulator-0.1.0-SNAPSHOT-all.jar /app/simulator.jar
ENTRYPOINT ["java", "-jar", "/app/simulator.jar"]
