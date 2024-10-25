def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        defaultContainer 'docker-ci-worker'
        yamlFile "${pipelineParams.git_subdir_name}/cd/pipelines/sre/${pipelineParams.crate_package_name}/KubernetesPod.yml"
        activeDeadlineSeconds 7200
      }
    }
    triggers {
        cron('H H * * *')
    }
    options {
      checkoutToSubdirectory pipelineParams.git_repo_name
    }
    stages {
      stage('Build & Push') {
        steps {
          dir(pipelineParams.git_repo_name) {
            script {
              withCredentials([
                usernamePassword(credentialsId: 'artifactory-jenkins', usernameVariable: 'ARTI_JENKINS_USER', passwordVariable: 'ARTI_JENKINS_PASS')
              ]) {
                withEnv(["BIO_ROOT=${env.WORKSPACE}/${pipelineParams.git_repo_name}",
                          "GIT_SUBDIR_NAME=${pipelineParams.git_subdir_name}",
                          "TARGET_IMAGE_FULL=${pipelineParams.target_image_full}",
                          "PACKAGE_NAME=${pipelineParams.crate_package_name}",
                          "ENV_NAME=${pipelineParams.crate_env_name}"
                ]) {
                    sh "git config --global --add safe.directory ${BIO_ROOT}"
                    sh """
                      BASE_IMAGE=\$(grep 'base_image:' ${GIT_SUBDIR_NAME}/prod/packages/${PACKAGE_NAME}/${PACKAGE_NAME}.yml | awk '{print $2}')
                      if [ -n "\${BASE_IMAGE}" ]; then
                        if ! ./${GIT_SUBDIR_NAME}/prod/packages/base-containers/check_base_layer.sh \${BASE_IMAGE} ${TARGET_IMAGE_FULL}; then
                          ARTIFACT_VERSION="\$(date -u '+%Y%m%d')"
                          export TAG_NAME="security-\${ARTIFACT_VERSION}"
                          ### This should produce an image with the folowing tags: {environment_name}-latest and security-YYYYMMDD
                          ### The 'crate.sh' script uses the PACKAGE_NAME, ENV_NAME and TAG_NAME env vars
                          ${GIT_SUBDIR_NAME}/cd/tools/crate.sh
                        fi
                      fi
                    """
                }
              }
            }
          }
          slackSend(
            channel: pipelineParams.slack_channel_name,
            message: """${pipelineParams.slack_mention_name} A new `${pipelineParams.target_image_full}` image has been built.
            If your ArgoCD app has an integration with *argocd-image-updater*, the image will be deployed automatically.
            Consider building and deploying a production image, if no issue has arisen.
            See build details at: <${env.BUILD_URL}console|Jenkins>""",
            sendAsText: true
          )
        }
      }
    }
  }
}
