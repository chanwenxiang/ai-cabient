# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY services/common/common-core/pom.xml services/common/common-core/
COPY services/device-service/pom.xml services/device-service/
# Root pom lists all modules; Maven requires each module dir to exist even with -pl
COPY services/trade-service/pom.xml services/trade-service/
COPY edge/device-simulator/pom.xml edge/device-simulator/
COPY services/common/common-core/src services/common/common-core/src
COPY services/device-service/src services/device-service/src

# 缓存 .m2 加速重复构建；去掉 -q 以便看到下载/编译进度
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn package -DskipTests -pl services/device-service -am -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data/mqtt-paho \
    && chown -R app:app /app

COPY --from=build --chown=app:app /build/services/device-service/target/device-service-*.jar app.jar

USER app

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
