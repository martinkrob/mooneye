# --- Build -------------------------------------------------------------------------------------
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# Dependencies first, so a source-only change does not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# --- Runtime -----------------------------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25-jre

# Fixed UID/GID 1000 so that rootless podman with `userns_mode: keep-id` maps the container user
# onto the usual first desktop user, and the bind-mounted ./data stays writable without chown.
RUN (getent passwd 1000 && userdel -r "$(getent passwd 1000 | cut -d: -f1)" || true) \
    && groupadd --gid 1000 mooneye \
    && useradd --uid 1000 --gid 1000 --home-dir /data --no-create-home mooneye \
    && mkdir -p /data /app \
    && chown mooneye:mooneye /data

COPY --from=build /src/target/mooneye.jar /app/mooneye.jar

ENV MOONEYE_DATA_PATH=/data \
    MOONEYE_PORT=8080

VOLUME /data
EXPOSE 8080
USER mooneye

# The model needs ~0.5-1 GB with the JVM; let the heap follow the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/mooneye.jar"]
