# Self-hosted GHA runner：预装 Java/Maven/Sonar Scanner，避免 DinD 卷挂载失败（Windows Docker Desktop）
FROM myoung34/github-runner:2.321.0

ARG SONAR_SCANNER_VERSION=6.2.1.4610

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless \
        maven \
        curl \
        unzip \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN curl -fsSL \
      "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux-x64.zip" \
      -o /tmp/sonar-scanner.zip \
    && unzip -q /tmp/sonar-scanner.zip -d /opt \
    && ln -sf "/opt/sonar-scanner-${SONAR_SCANNER_VERSION}-linux-x64/bin/sonar-scanner" /usr/local/bin/sonar-scanner \
    && rm -f /tmp/sonar-scanner.zip

RUN java -version && mvn -version && sonar-scanner --version
