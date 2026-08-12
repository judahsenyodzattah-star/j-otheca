# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 libranet
COPY --from=build /app/target/libranet-backend-0.1.0-SNAPSHOT.jar app.jar
USER libranet
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
