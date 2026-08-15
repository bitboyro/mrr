# syntax=docker/dockerfile:1
#
# Node builds the Next static export into src/main/resources/static/, then Maven
# packages the Spring Boot jar. One process, one container.
#
#   docker build -t mrr .
#   docker compose up

FROM node:22-alpine AS ui
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
COPY ui/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ ./src/
COPY --from=ui /ui/out/ ./src/main/resources/static/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app

# /data holds the SQLite database, the root token, and nothing else. Mount it.
RUN useradd --system --uid 10001 --create-home mrr \
 && mkdir -p /data \
 && chown -R mrr:mrr /data /app
VOLUME /data

COPY --from=build /src/target/mrr.jar /app/mrr.jar

USER mrr
EXPOSE 8130
ENV MRR_DB_PATH=/data/mrr.db \
    MRR_ROOT_TOKEN_FILE=/data/root-token.txt

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
  CMD ["sh", "-c", "wget -qO- http://localhost:8130/health || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/mrr.jar"]
