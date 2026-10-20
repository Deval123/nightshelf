# Build stage: compile and package the runnable jar.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Runtime stage: Java 21 JRE and ffmpeg, as an unprivileged user.
FROM eclipse-temurin:21.0.5_11-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 nightshelf \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin nightshelf \
    && mkdir /data \
    && chown 10001:10001 /data
COPY --from=build /src/target/nightshelf-*.jar /app/nightshelf.jar
USER 10001:10001
ENV CLIPBANK_DIR=/clipbank \
    DB_PATH=/data/index.db
ENTRYPOINT ["java", "-jar", "/app/nightshelf.jar"]
CMD ["run"]
