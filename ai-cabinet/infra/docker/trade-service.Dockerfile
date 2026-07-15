# syntax=docker/dockerfile:1
# trade-service 镜像：Maven 全量打包（含 clients/admin → static/admin 运营后台）

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY services/common/common-core/pom.xml services/common/common-core/
COPY services/trade-service/pom.xml services/trade-service/
# Root pom lists all modules; Maven requires each module dir to exist even with -pl
COPY services/device-service/pom.xml services/device-service/
COPY edge/device-simulator/pom.xml edge/device-simulator/

# 运营后台源码（frontend-maven-plugin 在 generate-resources 阶段 npm run build）
COPY clients/admin/package.json clients/admin/package-lock.json clients/admin/
COPY clients/admin/index.html clients/admin/vite.config.js clients/admin/
COPY clients/admin/src clients/admin/src

COPY services/common/common-core/src services/common/common-core/src
COPY services/trade-service/src services/trade-service/src

# 缓存 .m2 与 Node；用 -B 输出进度（含 npm install/build，首次约 10～20 分钟）
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    --mount=type=cache,target=/build/services/trade-service/target/frontend,sharing=locked \
    mvn package -DskipTests -pl services/trade-service -am -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/services/trade-service/target/trade-service-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
