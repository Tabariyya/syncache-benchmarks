# Use Maven image for building
FROM maven:3.9.9-eclipse-temurin-24 AS builder
WORKDIR /app
RUN apt update && apt install -y gnupg ca-certificates mono-complete

COPY pom.xml .
COPY src ./src
COPY synCache-java /root/.m2/repository/io/github/waleedsda/synCache-java
COPY syncache-spring-boot-starter /root/.m2/repository/io/github/waleedsda/syncache-spring-boot-starter
RUN mvn clean install

CMD ["mvn","clean","test"]