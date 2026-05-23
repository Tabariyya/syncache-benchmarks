FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /src
COPY pom.xml .
COPY src/ src/
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre
COPY --from=builder /src/target/syncache-benchmark-1.0.0.jar /app/benchmark.jar
ENTRYPOINT ["java", "-jar", "/app/benchmark.jar"]
