# The API. Java 8 bytecode (ADR 0001), built on a modern JDK.
#
# Two stages: build with a full JDK, run on a JRE. The runtime image never
# contains a compiler or the Maven cache.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependency layer first, so a source-only change does not re-resolve the world.
COPY backend/pom.xml backend/mvnw ./
COPY backend/.mvn ./.mvn
COPY backend/*/pom.xml ./
RUN ./mvnw -B -q de.qaware.maven:go-offline-maven-plugin:resolve-dependencies || true
COPY backend/ ./
RUN ./mvnw -B -DskipTests package

# The output is Java 8 bytecode, so it runs on a client's ancient JRE. We ship a
# modern one because we control this image; --release 8 is what makes the
# artifact portable to sites that do not.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10002 coreintra \
    && mkdir -p /var/lib/coreintra/blobs /var/lib/coreintra/fonts \
    && chown -R coreintra:coreintra /var/lib/coreintra
COPY --from=build /src/app/target/app-*.jar /app/app.jar
USER coreintra
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
