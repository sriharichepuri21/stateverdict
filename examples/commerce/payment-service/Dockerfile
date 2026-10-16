# Build context is the repository root (see infrastructure/compose/docker-compose.yml).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY libs/stateverdict-dispatch/pom.xml libs/stateverdict-dispatch/pom.xml
COPY examples/commerce/order-service/pom.xml examples/commerce/order-service/pom.xml
COPY examples/commerce/payment-service/pom.xml examples/commerce/payment-service/pom.xml
COPY libs/stateverdict-dispatch/src libs/stateverdict-dispatch/src
COPY examples/commerce/payment-service/src examples/commerce/payment-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl examples/commerce/payment-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /src/examples/commerce/payment-service/target/payment-service-*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
