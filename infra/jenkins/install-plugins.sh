#!/usr/bin/env bash
set -euo pipefail
MARKER=/var/jenkins_home/.ai-cabinet-plugins-installed
if [[ ! -f "$MARKER" ]]; then
  echo "==> Installing Jenkins plugins (official update center)..."
  export JENKINS_UC="${JENKINS_UC:-https://updates.jenkins.io}"
  export JENKINS_UC_DOWNLOAD="${JENKINS_UC_DOWNLOAD:-https://updates.jenkins.io/download}"
  jenkins-plugin-cli \
    --plugin-file /usr/share/jenkins/ref/plugins.txt \
    --plugin-download-directory /var/jenkins_home/plugins \
    --jenkins-update-center "${JENKINS_UC}/update-center.json" \
    --jenkins-plugin-info "${JENKINS_UC}/plugin-versions.json" \
    || echo "WARN: plugin install had errors; Jenkins will still start"
  touch "$MARKER"
fi
exec /usr/local/bin/jenkins.sh "$@"
