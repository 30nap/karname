# syntax=docker/dockerfile:1

# ---- build: compile and package the Spring Boot jar
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --quiet bootJar \
 && java -Djarmode=tools -jar build/libs/karname.jar extract --layers --destination extracted

# ---- runtime: a JRE image running as an unprivileged user
FROM eclipse-temurin:25-jre
RUN groupadd --system karname \
 && useradd --system --gid karname --home-dir /app --shell /usr/sbin/nologin karname \
 && mkdir -p /app/data && chown karname:karname /app/data
WORKDIR /app
# least to most frequently changed, so rebuilds reuse the dependency layers
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER karname
ENV KARNAME_SECRET_KEY_FILE=/app/data/secret.key
EXPOSE 8080
# no curl in the image: bash talks HTTP to the liveness probe directly
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "karname.jar"]
