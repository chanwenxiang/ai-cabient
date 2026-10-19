# syntax=docker/dockerfile:1
# trade-service 镜像：Maven 全量打包（含 clients/admin-vue → static/admin 运营控制台）

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY services/common/common-core/pom.xml services/common/common-core/
COPY services/trade-service/pom.xml services/trade-service/
COPY services/device-service/pom.xml services/device-service/
COPY edge/device-simulator/pom.xml edge/device-simulator/
# 将邑 CB-022：trade 镜像 reactor 需要新模块 pom 存在才能解析（-pl trade -am 不构建 gateway，但根 pom 声明了该模块）
COPY services/jiangyi-gateway/pom.xml services/jiangyi-gateway/

COPY packages/shared-dict/package.json packages/shared-dict/
COPY packages/shared-types/package.json packages/shared-types/
COPY packages/shared-api/package.json packages/shared-api/
COPY packages/shared-uni/package.json packages/shared-uni/
COPY packages/shared-dict/src packages/shared-dict/src
COPY packages/shared-types/src packages/shared-types/src
COPY packages/shared-api/src packages/shared-api/src
COPY packages/shared-uni/src packages/shared-uni/src

COPY clients/admin-vue/package.json clients/admin-vue/
COPY clients/admin-vue/index.html clients/admin-vue/vite.config.ts clients/admin-vue/tsconfig.json clients/admin-vue/
COPY clients/admin-vue/public clients/admin-vue/public
COPY clients/admin-vue/src clients/admin-vue/src

COPY services/common/common-core/src services/common/common-core/src
COPY services/trade-service/src services/trade-service/src

# 使用宿主机已构建的 static/admin（vite outDir），避免 BuildKit frontend 缓存导致运营台 CSS/JS 陈旧。
# 改 admin-vue 后请先在仓库根执行: node scripts/build-admin.mjs
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn package -DskipTests -pl services/trade-service -am -B -Pskip-admin-ui

# 日期/时区统一（2026-10-14）：JVM 默认时区统一为北京时间——此前容器默认 UTC，
# 日志时间戳慢 8 小时、裸 LocalDate.now() 凌晨 0-8 点算错日期（用户要求项目日期统一）。
# 定时任务 cron 仍显式 zone=Asia/Shanghai（aicabinet.schedule.zone），语义一致。
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata
ENV TZ=Asia/Shanghai
WORKDIR /app

RUN apk add --no-cache wget \
    && addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

COPY --chown=app:app --from=build /build/services/trade-service/target/trade-service-*.jar app.jar

USER app

EXPOSE 8080

# 堆随容器走（2026-10-16）：此前 ENTRYPOINT 是写死的 `java -jar`，全仓零 JVM 参数，
# 而 apps.yml 给本容器 `mem_limit: 1024m` —— JVM 默认 MaxRAMPercentage=25% ⇒ 实际堆只有 ~256MB，
# 且运维想调参必须重 build 镜像。`${JAVA_OPTS:-…}` 形式让「不设 = 用这套默认」，
# 设了 = 整体替换（`:-` 连空串也当未设，避免 compose 传 `JAVA_OPTS: ""` 把默认清空）。
# `exec` 让 java 仍是 PID 1：SIGTERM 直达 JVM，`stop_grace_period: 30s` 才有意义
# （见 scripts/check-compose-stop-grace.mjs 头注：装了宽限不处理信号 = 白等后 137）。
# ExitOnOutOfMemoryError：宁可被 `restart: unless-stopped` 拉起，也不要 OOM 后僵着半死进程。
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs} -jar app.jar"]
