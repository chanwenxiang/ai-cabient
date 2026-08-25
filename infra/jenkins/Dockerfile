# Jenkins + Maven（流水线直接 mvn，不依赖容器内 docker CLI）
FROM jenkins/jenkins:lts-jdk17
USER root

# Maven：本机构建 / Sonar 上传
# docker CLI：可选，compose-local 部署时用（配合挂载的 docker.sock）
RUN apt-get update \
  && apt-get install -y --no-install-recommends maven ca-certificates curl gnupg \
  && install -m 0755 -d /etc/apt/keyrings \
  && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
  && chmod a+r /etc/apt/keyrings/docker.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable" \
       > /etc/apt/sources.list.d/docker.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
  && rm -rf /var/lib/apt/lists/* \
  && mvn -version \
  && docker --version

COPY casc.yaml /var/jenkins_config/casc.yaml
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
COPY install-plugins.sh /usr/local/bin/install-plugins-ai.sh
RUN chmod +x /usr/local/bin/install-plugins-ai.sh

ENV CASC_JENKINS_CONFIG=/var/jenkins_config/casc.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Djava.awt.headless=true"
ENV JENKINS_UC=https://updates.jenkins.io
ENV JENKINS_UC_DOWNLOAD=https://updates.jenkins.io/download

# 保持 root 以便访问 docker.sock；生产可改为 jenkins + docker 组
USER root
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/install-plugins-ai.sh"]
