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

            stage('CI') {
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
                        def javaScriptContent = libraryResource('Dockerfile_java8')
                        writeFile file: 'Dockerfile_java8', text: javaScriptContent

                        sh "pwd"
                        sh 'chmod +x deploy.sh'

                        // get version from pom.xml
                        def version = null
                        def lg = "none"
                        if (fileExists("${code_dir}/pom.xml")) {
                            version = sh(
                                    script: "cat ${code_dir}/pom.xml | grep '<version>' | head -1 | sed 's/.*<version>\\(.*\\)<\\/version>.*/\\1/'",
                                    returnStdout: true
                            ).trim()
                            lg = "java"
                            echo "Extracted version from pom.xml: ${version}"
                        } else if (fileExists("${code_dir}/package.json")) {
                            version = sh(
                                    script: "cat ${code_dir}/package.json | grep '\"version\"' | head -1 | sed 's/.*\"version\"\\s*:\\s*\"\\([^\"]*\\)\".*/\\1/'",
                                    returnStdout: true
                            ).trim()
                            echo "Extracted version from package.json: ${version}"
                        } else if (fileExists("${code_dir}/setup.py")) {
                            version = sh(
                                    script: "grep -E \"version\\s*=\\s*['\\\"]\" ${code_dir}/setup.py | head -1 | sed \"s/.*version\\s*=\\s*['\\\"]\\([^'\\\"]*\\)['\\\"].*/\\1/\"",
                                    returnStdout: true
                            ).trim()
                            echo "Extracted version from setup.py: ${version}"
                        } else {
                            try {
                                version = sh(script: "cd ${code_dir} && git describe --tags --always --dirty", returnStdout: true).trim()
                                echo "Extracted version from git describe: ${version}"
                            } catch (Exception e) {
                                version = 'latest'
                                echo "No version file found, using fallback version: ${version}"
                            }
                        }
                        env.VERSION = version ?: 'latest'
                        env.LG = lg
                        echo "Final version to use: ${env.VERSION}"


//                        use credentials 097d9c91-53ff-4068-8a37-9b5a3cd7485d get username and password, pass them to deploy.sh as parameters.
                        withCredentials([usernamePassword(credentialsId: '097d9c91-53ff-4068-8a37-9b5a3cd7485d', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                            sh "./deploy.sh ${code_dir} ${params.API_PORT} '${params.envs}' ${params.api_name} ${USERNAME} ${PASSWORD}"
                        }
                        // 执行部署脚本

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
            // run on node with label 'w-ubuntu-k3s' to deploy to k3s cluster
            stage('CD') {
//                agent { label 'w-ubuntu-k3s' }
                steps {
                    script {
                        // 1️⃣ checkout 应用代码（如果需要）
                        // 2️⃣ checkout Helm 仓库
                        dir('devops-learn') {
                            git(
                                    url: 'https://github.com/jiangchengyu998/devops-learn.git',
                                    branch: 'main'
//                                    credentialsId: 'git-ssh-key'   // 你 Jenkins 配的凭证
                            )
                        }

                        // 3️⃣ 定义 charts_dir
                        def charts_dir = "${env.WORKSPACE}/devops-learn/charts"

                        def heml_dir = "springboot-api"

                        // 如果 env.LG = "java"，则使用 java8 的 helm chart
                        if (env.LG == "java") {
                            heml_dir = "springboot-api"
                        }  else {
                            heml_dir = "generic-api"
                        }

                        // 4️⃣ 验证目录（非常重要）
                        sh """
                            echo "==== 检查 Helm charts ===="
                            ls -al ${charts_dir}
                            ls -al ${charts_dir}/${heml_dir}
                        """

                        // 5️⃣ 准备 deploy 脚本（来自 shared library）
                        def scriptContent = libraryResource('deploy_helm.sh')
                        writeFile file: 'deploy_helm.sh', text: scriptContent
                        sh 'chmod +x deploy_helm.sh'

                        // 6️⃣ 变量定义
                        def chartPath = "${charts_dir}/${heml_dir}"
                        def host = "${params.api_name}.ydphoto.com"
                        def releaseName = "${params.api_name}"
                        def version = "${env.VERSION}"
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
                    sendWebhookWithRetry(params.api_id, "RUNNING", env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
            failure {
                echo "Deployment failed"
                script {
                    echo "准备发送 RUNNING 状态的 webhook 回调"

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