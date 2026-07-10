# syntax=docker/dockerfile:1
# trade-service 镜像：Maven 全量打包（含 clients/admin-vue → static/admin 运营控制台）

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY services/common/common-core/pom.xml services/common/common-core/
COPY services/trade-service/pom.xml services/trade-service/
COPY services/device-service/pom.xml services/device-service/
COPY edge/device-simulator/pom.xml edge/device-simulator/

COPY packages/shared-dict/package.json packages/shared-dict/
COPY packages/shared-types/package.json packages/shared-types/
COPY packages/shared-api/package.json packages/shared-api/
COPY packages/shared-dict/src packages/shared-dict/src
COPY packages/shared-types/src packages/shared-types/src
COPY packages/shared-api/src packages/shared-api/src

COPY clients/admin-vue/package.json clients/admin-vue/package-lock.json clients/admin-vue/
COPY clients/admin-vue/index.html clients/admin-vue/vite.config.ts clients/admin-vue/tsconfig.json clients/admin-vue/
COPY clients/admin-vue/src clients/admin-vue/src

COPY services/common/common-core/src services/common/common-core/src
COPY services/trade-service/src services/trade-service/src

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
