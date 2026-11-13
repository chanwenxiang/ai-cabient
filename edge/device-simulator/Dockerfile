FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl edge/device-simulator -am package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# R19-17 同款时区双钉（2026-11-12 第 2 批，check-tz-pinned 首跑抓到）：模拟器上报的时间戳
# 与 trade 侧 ScheduleZones 对齐，UTC 裸跑会让 UAT 数据落在错误业务日。
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai
COPY --from=build /workspace/edge/device-simulator/target/device-simulator-0.1.0-SNAPSHOT-all.jar /app/simulator.jar
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/simulator.jar"]
