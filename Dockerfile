FROM gradle:9.8.2-jdk25 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:25.0.4.1_1-jre
LABEL org.opencontainers.image.source="https://github.com/PawelWywiol/minerva-helper-web-app"
LABEL org.opencontainers.image.licenses="MIT"
COPY --from=build /src/build/install/minerva-helper-web-app /app
USER 1000:1000
EXPOSE 8080
VOLUME ["/data", "/library"]
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 CMD ["/app/bin/minerva-helper-web-app", "healthcheck"]
ENTRYPOINT ["/app/bin/minerva-helper-web-app"]
