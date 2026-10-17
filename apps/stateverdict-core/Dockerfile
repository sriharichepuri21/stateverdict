# StateVerdict CLI image, so the demo needs nothing but Docker.
# Build context is the repository root (see infrastructure/compose/docker-compose.yml).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY libs/stateverdict-dispatch/pom.xml libs/stateverdict-dispatch/pom.xml
COPY apps/stateverdict-core/pom.xml apps/stateverdict-core/pom.xml
COPY examples/commerce/order-service/pom.xml examples/commerce/order-service/pom.xml
COPY examples/commerce/payment-service/pom.xml examples/commerce/payment-service/pom.xml
COPY apps/stateverdict-core/src apps/stateverdict-core/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl apps/stateverdict-core -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /work
COPY --from=build /src/apps/stateverdict-core/target/stateverdict-core-*.jar /opt/stateverdict/stateverdict.jar
ENTRYPOINT ["java", "-jar", "/opt/stateverdict/stateverdict.jar"]
