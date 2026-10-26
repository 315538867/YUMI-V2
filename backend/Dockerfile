FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -Dmaven.test.skip=true dependency:resolve
COPY src/ src/
RUN ./mvnw -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx1g"

RUN groupadd --system --gid 10001 yumi \
    && useradd --system --uid 10001 --gid yumi --home-dir /app --shell /usr/sbin/nologin yumi \
    && mkdir -p /app /var/lib/yumi/files \
    && chown -R yumi:yumi /app /var/lib/yumi

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown yumi:yumi /app/app.jar

USER yumi
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
