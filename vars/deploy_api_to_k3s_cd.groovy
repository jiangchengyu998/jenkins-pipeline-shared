// vars/deploy_api_to_k3s_cd.groovy
import groovy.json.JsonOutput

def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu' }
        options {
            timestamps()
            skipDefaultCheckout(true)
            disableConcurrentBuilds()
            timeout(time: config.timeoutMinutes ?: 30, unit: 'MINUTES')
        }
        environment {
            http_proxy  = ''
            https_proxy = ''
            HTTP_PROXY  = ''
            HTTPS_PROXY = ''
        }
        parameters {
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'Execution node')
            string(name: 'api_id', defaultValue: config.api_id ?: '', description: 'API ID')
            string(name: 'api_name', defaultValue: config.api_name ?: '', description: 'API name / Helm release name')
            string(name: 'CALL_BACK_HOST', defaultValue: config.call_back_host ?: '', description: '构建完成后的回调地址')
            password(name: 'WEBHOOK_SECRET', defaultValue: config.webhookSecret ?: '', description: 'Webhook 回调密钥')
            string(name: 'TAG', defaultValue: config.tag ?: '', description: 'Image tag to deploy')
            string(name: 'envs', defaultValue: config.envs ?: '', description: 'Environment variables as JSON')
            string(name: 'HELM_GIT_URL', defaultValue: config.helmGitUrl ?: 'git@github.com:jiangchengyu998/devops-learn.git', description: 'Helm charts repository URL')
            string(name: 'HELM_GIT_BRANCH', defaultValue: config.helmGitBranch ?: 'main', description: 'Helm charts repository branch')
            string(name: 'HELM_GIT_CREDENTIALS_ID', defaultValue: config.helmGitCredentialsId ?: '', description: 'Jenkins credentials ID for Helm repository')
            string(name: 'HELM_ENV', defaultValue: config.helmEnv ?: 'dev', description: 'Helm environment value')
            string(name: 'HELM_HOST_SUFFIX', defaultValue: config.helmHostSuffix ?: 'ydphoto.com', description: 'Ingress host suffix')
            string(name: 'HELM_CHART', defaultValue: config.helmChart ?: 'springboot-api', description: 'Helm chart name')
            string(name: 'HELM_NAMESPACE', defaultValue: config.helmNamespace ?: 'default', description: 'Kubernetes namespace')
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        validateRequiredParams()
                        echo 'Cleaning workspace...'
                        deleteDir()
                        echo 'Workspace cleaned successfully'
                    }
                }
            }

            stage('CD') {
                steps {
                    script {
                        checkoutHelmRepository()

                        def chartsDir = "${env.WORKSPACE}/devops-learn/charts"
                        def chartName = params.HELM_CHART.trim()
                        def chartPath = "${chartsDir}/${chartName}"

                        sh(
                                label: 'Validate Helm chart path',
                                script: """
                                    set -e
                                    test -d ${shellQuote(chartsDir)}
                                    test -f ${shellQuote(chartPath + '/values.yaml')}
                                """.stripIndent()
                        )

                        writeFile file: 'deploy_helm.sh', text: libraryResource('deploy_helm.sh')
                        sh 'chmod +x deploy_helm.sh'

                        def host = buildHost(params.api_name, params.HELM_HOST_SUFFIX)
                        def releaseName = params.api_name.trim()
                        def version = sanitizeDockerTag(params.TAG)
                        def envName = params.HELM_ENV.trim()
                        def namespace = params.HELM_NAMESPACE.trim()

                        echo """
                            ===== Helm Deploy =====
                            chartPath: ${chartPath}
                            host: ${host}
                            releaseName: ${releaseName}
                            version: ${version}
                            environment: ${envName}
                            namespace: ${namespace}
                            =======================
                        """.stripIndent()

                        withEnv([
                                "CHART_PATH=${chartPath}",
                                "HELM_HOST=${host}",
                                "RELEASE_NAME=${releaseName}",
                                "IMAGE_VERSION=${version}",
                                "HELM_ENV_NAME=${envName}",
                                "HELM_NAMESPACE_NAME=${namespace}",
                                "HELM_APP_ENVS=${params.envs ?: ''}"
                        ]) {
                            sh(
                                    label: 'Deploy Helm release',
                                    script: '''
                                        set +x
                                        set -e
                                        ./deploy_helm.sh "$CHART_PATH" "$HELM_HOST" "$RELEASE_NAME" "$IMAGE_VERSION" "$HELM_ENV_NAME" "$HELM_NAMESPACE_NAME"
                                    '''.stripIndent()
                            )
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment api ${params.api_id} completed successfully with tag ${params.TAG}"
                script {
                    echo '准备发送 RUNNING 状态的 webhook 回调'
                    sendWebhookWithRetry(params.api_id, 'RUNNING', env.BUILD_ID, 3, params.CALL_BACK_HOST, params.WEBHOOK_SECRET?.toString())
                }
            }
            failure {
                echo 'Deployment failed'
                script {
                    echo '准备发送 ERROR 状态的 webhook 回调'
                    sendWebhookWithRetry(params.api_id, 'ERROR', env.BUILD_ID, 3, params.CALL_BACK_HOST, params.WEBHOOK_SECRET?.toString())
                }
            }
        }
    }
}

def validateRequiredParams() {
    def required = [
            api_name: params.api_name,
            TAG: params.TAG,
            HELM_GIT_URL: params.HELM_GIT_URL,
            HELM_GIT_BRANCH: params.HELM_GIT_BRANCH,
            HELM_ENV: params.HELM_ENV,
            HELM_HOST_SUFFIX: params.HELM_HOST_SUFFIX,
            HELM_CHART: params.HELM_CHART,
            HELM_NAMESPACE: params.HELM_NAMESPACE
    ]

    def missing = required.findAll { !it.value?.trim() }.keySet()
    if (missing) {
        error "Missing required parameters: ${missing.join(', ')}"
    }

    validateKubernetesName(params.api_name, 'api_name')
    validateKubernetesName(params.HELM_NAMESPACE, 'HELM_NAMESPACE')
}

def checkoutHelmRepository() {
    dir('devops-learn') {
        if (params.HELM_GIT_CREDENTIALS_ID?.trim()) {
            git(
                    url: params.HELM_GIT_URL,
                    branch: params.HELM_GIT_BRANCH,
                    credentialsId: params.HELM_GIT_CREDENTIALS_ID.trim()
            )
        } else {
            git(
                    url: params.HELM_GIT_URL,
                    branch: params.HELM_GIT_BRANCH
            )
        }
    }
}

def buildHost(String apiName, String suffix) {
    def cleanSuffix = suffix.trim().replaceFirst(/^\.+/, '')
    return "${apiName.trim()}.${cleanSuffix}"
}

def validateKubernetesName(String value, String label) {
    def name = value?.trim()
    if (!(name ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error "${label} must be a valid Kubernetes DNS label: ${value}"
    }
}

def sanitizeDockerTag(String value) {
    def tag = (value ?: '').trim().replaceAll(/[^A-Za-z0-9_.-]/, '-')
    if (!tag) {
        error 'TAG is empty after sanitizing'
    }
    return tag.take(128)
}

def shellQuote(String value) {
    return "'" + (value ?: '').replace("'", "'\"'\"'") + "'"
}

def sendWebhookWithRetry(apiId, status, jobId, maxRetries = 3, callBackHost, webhookSecret = '') {
    if (!apiId?.trim() || !callBackHost?.trim() || !webhookSecret?.trim()) {
        echo 'Webhook skipped: api_id, CALL_BACK_HOST, or WEBHOOK_SECRET is empty'
        return false
    }

    def retryDelay = 5
    def payloadFile = "webhook-${status.toLowerCase()}-${jobId}.json"
    writeFile file: payloadFile, text: JsonOutput.toJson([apiStatus: status, jobId: jobId])
    def webhookUrl = "${callBackHost.replaceAll(/\/+$/, '')}/api/apis/${apiId}/webhook"

    for (int i = 0; i < maxRetries; i++) {
        try {
            echo "Sending ${status} webhook - Attempt ${i + 1}/${maxRetries}"

            def result = sh(
                    script: """curl --location ${shellQuote(webhookUrl)} \\
                    --header 'Content-Type: application/json' \\
                    --header ${shellQuote('x-webhook-secret: ' + webhookSecret)} \\
                    --data @${shellQuote(payloadFile)} \\
                    --insecure \\
                    --ssl-no-revoke \\
                    --connect-timeout 10 \\
                    --max-time 30 \\
                    --silent \\
                    --show-error""",
                    returnStatus: true
            )

            if (result == 0) {
                echo "${status} webhook sent successfully"
                return true
            }

            echo "Webhook attempt ${i + 1} failed with exit code: ${result}"
            if (i < maxRetries - 1) {
                sleep(retryDelay)
            }
        } catch (Exception e) {
            echo "Webhook attempt ${i + 1} threw exception: ${e.message}"
            if (i < maxRetries - 1) {
                sleep(retryDelay)
            }
        }
    }

    echo "Failed to send ${status} webhook after ${maxRetries} attempts"
    return false
}
