
FROM maven:3.9.6-eclipse-temurin-17 as builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean install -DskipTests


FROM openjdk:17-jdk-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    fontconfig \
    libfreetype6 \
    fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8000

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app.jar"]

#docker build -t pdf-tagging .
#docker run -p 8000:8000 -e AWS_ACCESS_KEY="value" -e AWS_SECRET_KEY="value" pdf-tagging