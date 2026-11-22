# syntax=docker/dockerfile:1
# Imagen del backend: compila con Maven y ejecuta con un JRE mínimo, sin root.

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
# Primero solo el POM: las dependencias quedan en caché mientras no cambie.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
# Las pruebas corren en CI (con Testcontainers); aquí solo se empaqueta.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package \
    && cp target/billing-platform-backend-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
