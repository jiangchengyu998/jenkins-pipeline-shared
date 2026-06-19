// vars/deploy_api_to_k3s.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu'}
        options {
            // 每一行日志前加上时间戳
            timestamps()
        }
        environment {
            http_proxy  = ''
            https_proxy = ''
            HTTP_PROXY  = ''
            HTTPS_PROXY = ''
        }
        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'API_PORT', defaultValue: config.apiPort ?: '3000', description: 'API port number')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'Execution node')
            string(name: 'branch', defaultValue: config.branch ?: 'main', description: 'Git branch')
            string(name: 'envs', defaultValue: config.envs ?: '', description: 'Environment variables')
            string(name: 'api_id', defaultValue: config.api_id ?: '', description: 'API ID')
            string(name: 'gitToken', defaultValue: config.gitToken ?: '', description: 'Git gitToken for authentication')
            string(name: 'api_name', defaultValue: config.api_name ?: '', description: 'api_name')
            string(name: 'CALL_BACK_HOST', defaultValue: config.call_back_host ?: '', description: '构建完成后的回调地址')
            string(name: 'TAG', defaultValue: config.tag ?: '', description: 'Tag')
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        echo "Cleaning workspace..."
                        deleteDir()
                        echo "Workspace cleaned successfully"
                        sh "ls -la"
                    }
                }
            }


            // run on node with label 'w-ubuntu-k3s' to deploy to k3s cluster
            stage('CD') {
                steps {
                    script {
                        // 1️⃣ checkout 应用代码（如果需要）
                        // 2️⃣ checkout Helm 仓库
                        dir('devops-learn') {
                            git(
                                    url: 'git@github.com:jiangchengyu998/devops-learn.git',
                                    branch: 'main'
//                                    credentialsId: 'git-ssh-key'   // 你 Jenkins 配的凭证
                            )
                        }

                        // 3️⃣ 定义 charts_dir
                        def charts_dir = "${env.WORKSPACE}/devops-learn/charts"

                        // 4️⃣ 验证目录（非常重要）
                        sh """
                            echo "==== 检查 Helm charts ===="
                            ls -al ${charts_dir}
                            ls -al ${charts_dir}/springboot-api
                        """

                        // 5️⃣ 准备 deploy 脚本（来自 shared library）
                        def scriptContent = libraryResource('deploy_helm.sh')
                        writeFile file: 'deploy_helm.sh', text: scriptContent
                        sh 'chmod +x deploy_helm.sh'

                        // 6️⃣ 变量定义
                        def chartPath = "${charts_dir}/springboot-api"
                        def host = "${params.api_name}.ydphoto.com"
                        def releaseName = "${params.api_name}"
                        def version = "${params.TAG}"
                        def envName = "dev"

                        // 7️⃣ 打印信息
                        echo """
                            ===== Helm Deploy =====
                            chartPath: ${chartPath}
                            host: ${host}
                            releaseName: ${releaseName}
                            =======================
                            """

                        // 8️⃣ 执行部署
                        sh """
                                set -e
                                ./deploy_helm.sh \
                                    ${chartPath} \
                                    ${host} \
                                    ${releaseName} \
                                    ${version} \
                                    ${envName}
                            """
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment api ${params.api_id} completed successfully on port ${params.API_PORT}"
                script {
                    echo "准备发送 RUNNING 状态的 webhook 回调"
//                    sendWebhookWithRetry(params.api_id, "RUNNING", env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
            failure {
                echo "Deployment failed"
                script {
                    echo "准备发送 RUNNING 状态的 webhook 回调"

//                    sendWebhookWithRetry(params.api_id, "ERROR", env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
        }
    }
}

// vars/webhook_utils.groovy
def sendWebhookWithRetry(apiId, status, jobId, maxRetries = 3, callBackHost) {
    def retryDelay = 5

    for (int i = 0; i < maxRetries; i++) {
        try {
            echo "Sending ${status} webhook - Attempt ${i + 1}/${maxRetries}"

            def result = sh(
                    script: """curl --location '${callBackHost}/api/apis/${apiId}/webhook' \
                    --header 'Content-Type: application/json' \
                    --data '{
                        \"apiStatus\":\"${status}\",
                        \"jobId\": \"${jobId}\"
                    }' \
                    --insecure \
                    --ssl-no-revoke \
                    --connect-timeout 10 \
                    --max-time 30 \
                    --silent \
                    --show-error""",
                    returnStatus: true
            )

            if (result == 0) {
                echo "✅ ${status} webhook sent successfully"
                return true
            } else {
                echo "❌ Webhook attempt ${i + 1} failed with exit code: ${result}"
                if (i < maxRetries - 1) {
                    sleep(retryDelay)
                }
            }
        } catch (Exception e) {
            echo "❌ Webhook attempt ${i + 1} threw exception: ${e.message}"
            if (i < maxRetries - 1) {
                sleep(retryDelay)
            }
        }
    }

    echo "⚠️ Failed to send ${status} webhook after ${maxRetries} attempts"
    return false
}