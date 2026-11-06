FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/ms-pse-1.0.0.jar app.jar
ENV PORT=8009
EXPOSE 8009
ENTRYPOINT ["java", "-jar", "app.jar"]
