FROM eclipse-temurin:21-jdk-noble@sha256:dc333cdac241f073941f043792f2a2f9b790bfe7cd98ef2843c08880986708de AS build
# The checksum-verifying repository wrapper uses Python's standard library.
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/* \
    && install -d -o ubuntu -g ubuntu /workspace
WORKDIR /workspace
USER 1000:1000
COPY --chown=1000:1000 --chmod=755 .mvn/bootstrap.sh .mvn/bootstrap.sh
COPY --chown=1000:1000 --chmod=755 mvnw ./
COPY --chown=1000:1000 pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY --chown=1000:1000 src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-noble@sha256:54999a956a8bf8be9af0052790a6a832bf91a62fad3199fd194c3cc28057fc62
RUN install -d -o ubuntu -g ubuntu /app /var/lib/dormlink/uploads
WORKDIR /app
COPY --from=build /workspace/target/dormlink-backend-1.0.0.jar /app/backend.jar
USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s CMD curl -fsS -o /dev/null http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
