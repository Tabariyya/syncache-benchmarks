FROM maven:3.9.9-eclipse-temurin-24 AS builder
WORKDIR /app
RUN apt update && apt install -y gnupg ca-certificates mono-complete

COPY pom.xml .
COPY src ./src
RUN mvn clean install -DskipTests

CMD ["mvn","clean","test"]