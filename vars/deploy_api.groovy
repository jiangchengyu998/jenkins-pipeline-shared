// vars/deploy_api.groovy
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
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        echo "Cleaning workspace..."
                        deleteDir()
                        echo "Workspace cleaned successfully"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    script {
                        // 自动提取仓库名（取 URL 最后一段去掉 .git）
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')
                        echo "Repository name: ${repoName}"

                        // 如果有 gitToken，构造带认证的 URL
                        def gitUrlWithAuth = params.GIT_URL
                        if (params.gitToken) {
                            echo "Using gitToken authentication for Git clone"
                            // 处理不同的 Git 平台 URL 格式
                            if (params.GIT_URL.contains('github.com')) {
                                gitUrlWithAuth = params.GIT_URL.replace('https://', "https://oauth2:${params.gitToken}@")
                            } else if (params.GIT_URL.contains('gitlab.com')) {
                                gitUrlWithAuth = params.GIT_URL.replace('https://', "https://oauth2:${params.gitToken}@")
                            } else if (params.GIT_URL.contains('gitee.com')) {
                                gitUrlWithAuth = params.GIT_URL.replace('https://', "https://oauth2:${params.gitToken}@")
                            } else {
                                // 通用处理：在域名前插入 gitToken
                                def urlParts = params.GIT_URL.split('://')
                                if (urlParts.length == 2) {
                                    gitUrlWithAuth = "${urlParts[0]}://oauth2:${params.gitToken}@${urlParts[1]}"
                                }
                            }
                            echo "Using authenticated Git URL (gitToken hidden in logs)"
                        } else {
                            echo "No gitToken provided, using original Git URL"
                        }

                        // 在指定目录中 checkout
                        dir(repoName) {
                            try {
                                if (params.gitToken) {
                                    // 使用带认证的 URL 进行 clone
                                    sh "git clone -b ${params.branch} '${gitUrlWithAuth}' ."
                                } else {
                                    // 使用原始 URL
                                    git branch: "${params.branch}", url: "${params.GIT_URL}"
                                }

                                // 显示最近的提交信息
                                def lastCommit = sh(
                                        script: "git log --oneline -1",
                                        returnStdout: true
                                ).trim()
                                echo "Latest commit: ${lastCommit}"

                            } catch (Exception e) {
                                error "Git checkout failed: ${e.message}"
                            }
                        }
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')
                        def code_dir = "${pwd()}/${repoName}"

                        echo "Starting deployment..."
                        echo "Code directory: ${code_dir}"
                        echo "API port: ${params.API_PORT}"

                        // 从 shared library 的 resources 中加载 deploy.sh
                        def scriptContent = libraryResource('deploy.sh')
                        writeFile file: 'deploy.sh', text: scriptContent
                        def javaScriptContent = libraryResource('Dockerfile.java')
                        writeFile file: 'Dockerfile.java', text: javaScriptContent

                        sh "pwd"
                        sh 'chmod +x deploy.sh'

                        // 执行部署脚本
                        sh "./deploy.sh ${code_dir} ${params.API_PORT} '${params.envs}' ${params.api_name}"

                        // 验证部署结果
                        sh "ls -la"
                        def dockerStatus = sh(
                                script: "docker ps --format 'table {{.Names}}\\t{{.Status}}'",
                                returnStdout: true
                        ).trim()
                        echo "Docker containers status:\n${dockerStatus}"
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment api ${params.api_id} completed successfully on port ${params.API_PORT}"
                script {
                    sendWebhookWithRetry(params.api_id, "RUNNING", env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
            failure {
                echo "Deployment failed"
                script {
                    sendWebhookWithRetry(params.api_id, "ERROR", env.BUILD_ID, 3, params.CALL_BACK_HOST)
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