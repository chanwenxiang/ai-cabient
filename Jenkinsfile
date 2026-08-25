// 共用流水线：本机联调（挂载 /workspace）与服务器发布（GitHub checkout）同一份脚本
//
// 本机：USE_LOCAL_MOUNT=true → 直接用 /workspace，禁止全仓 cp
// 构建：容器内直接 mvn（需 Jenkins 镜像含 Maven）；不再 docker run maven
pipeline {
  agent any

  parameters {
    booleanParam(name: 'USE_LOCAL_MOUNT', defaultValue: false, description: '本机：直接用 /workspace，不拷贝')
    choice(name: 'DEPLOY_MODE', choices: ['none', 'compose-local', 'ssh'], description: '部署：none=只构建；compose-local=本机/同机；ssh=远程服务器')
    string(name: 'DEPLOY_HOST', defaultValue: '', description: 'ssh：服务器地址')
    string(name: 'DEPLOY_USER', defaultValue: 'deploy', description: 'ssh：用户')
    string(name: 'DEPLOY_PATH', defaultValue: '/opt/ai-cabinet', description: '服务器仓库目录')
  }

  environment {
    SONAR_HOST_URL = "${env.SONAR_HOST_URL ?: 'http://sonarqube:9000'}"
    SONAR_TOKEN = credentials('sonar-token')
  }

  stages {
    stage('Source') {
      steps {
        script {
          def useLocal = params.USE_LOCAL_MOUNT?.toString() == 'true' || env.USE_LOCAL_MOUNT == 'true'
          if (useLocal) {
            echo '本机模式：直接使用 /workspace（跳过全仓拷贝）'
            env.BUILD_DIR = '/workspace'
          } else {
            echo '正式模式：从 GitHub SCM checkout'
            checkout scm
            env.BUILD_DIR = env.WORKSPACE
          }
        }
      }
    }

    stage('Resolve Sonar project') {
      steps {
        script {
          def branch = env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'dev'
          branch = branch.replaceAll('^origin/', '')
          if (branch == 'main' || branch == 'master') {
            env.SONAR_PROJECT_KEY = 'ai-cabinet-main'
            env.SONAR_PROJECT_NAME = 'AI Cabinet (main)'
          } else {
            env.SONAR_PROJECT_KEY = 'ai-cabinet-dev'
            env.SONAR_PROJECT_NAME = 'AI Cabinet (dev)'
          }
          echo "Sonar projectKey=${env.SONAR_PROJECT_KEY} branch=${branch} BUILD_DIR=${env.BUILD_DIR}"
        }
      }
    }

    stage('Compile + Sonar') {
      steps {
        sh '''
          set -e
          BUILD_DIR="${BUILD_DIR:-$PWD}"
          command -v mvn >/dev/null || { echo "ERROR: Jenkins 镜像未安装 Maven"; exit 127; }
          cd "$BUILD_DIR"
          echo "Building in $(pwd) with $(mvn -version | head -1)"
          mvn -B -DskipTests -Dmaven.test.skip=true install \
            -pl services/trade-service,services/device-service,services/common/common-core -am
          mvn -B -DskipTests -Dmaven.test.skip=true sonar:sonar \
            -Dsonar.projectKey="$SONAR_PROJECT_KEY" \
            -Dsonar.projectName="$SONAR_PROJECT_NAME" \
            -Dsonar.host.url="$SONAR_HOST_URL" \
            -Dsonar.token="$SONAR_TOKEN" \
            -Dsonar.scm.revision="${GIT_COMMIT:-}"
        '''
      }
    }

    stage('Deploy') {
      when {
        allOf {
          anyOf {
            branch 'main'
            branch pattern: 'release/.*', comparator: 'REGEXP'
            tag pattern: 'v.*', comparator: 'REGEXP'
            expression {
              def useLocal = params.USE_LOCAL_MOUNT?.toString() == 'true' || env.USE_LOCAL_MOUNT == 'true'
              return useLocal && params.DEPLOY_MODE != 'none'
            }
          }
          expression { return params.DEPLOY_MODE != 'none' }
        }
      }
      steps {
        script {
          if (params.DEPLOY_MODE == 'compose-local') {
            dir(env.BUILD_DIR ?: env.WORKSPACE) {
              sh '''
                set -e
                command -v docker >/dev/null || { echo "ERROR: 未安装 docker CLI"; exit 127; }
                docker compose -f infra/docker-compose.full.yml up -d --build trade-service device-service
              '''
            }
          } else if (params.DEPLOY_MODE == 'ssh') {
            if (!params.DEPLOY_HOST?.trim()) {
              error('DEPLOY_MODE=ssh 时必须填写 DEPLOY_HOST')
            }
            sshagent(credentials: ['deploy-ssh-key']) {
              sh """
                set -e
                ssh -o StrictHostKeyChecking=accept-new ${params.DEPLOY_USER}@${params.DEPLOY_HOST} '
                  set -e
                  cd ${params.DEPLOY_PATH}
                  git fetch --all --tags
                  git checkout "${env.GIT_COMMIT}"
                  docker compose -f infra/docker-compose.full.yml up -d --build trade-service device-service
                '
              """
            }
          }
        }
      }
    }
  }
}
