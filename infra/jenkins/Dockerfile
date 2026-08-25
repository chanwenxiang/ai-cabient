# Prefer stock LTS + plugins installed into jenkins_home at first start
# (avoids flaky plugin mirrors during docker build).
FROM jenkins/jenkins:lts-jdk17
USER root
COPY casc.yaml /var/jenkins_config/casc.yaml
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
COPY install-plugins.sh /usr/local/bin/install-plugins-ai.sh
RUN chmod +x /usr/local/bin/install-plugins-ai.sh
ENV CASC_JENKINS_CONFIG=/var/jenkins_config/casc.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Djava.awt.headless=true"
ENV JENKINS_UC=https://updates.jenkins.io
ENV JENKINS_UC_DOWNLOAD=https://updates.jenkins.io/download
USER jenkins
# Entry: install plugins if missing, then start Jenkins
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/install-plugins-ai.sh"]
