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
                        sh "pwd"
                        sh 'chmod +x deploy.sh'

                        // 执行部署脚本
                        sh "./deploy.sh ${code_dir} ${params.API_PORT} '${params.envs}'"

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
            always {
                echo "Pipeline execution completed"
                // 清理临时文件
                sh "rm -f deploy.sh || true"
            }
            success {
                echo "Deployment api ${params.api_id} completed successfully on port ${params.API_PORT}"

                script {
                    try {
                        sh """curl --location 'https://www.ydphoto.com/api/apis/${params.api_id}/webhook' \
                            --header 'Content-Type: application/json' \
                            --data '{
                                \"apiStatus\":\"RUNNING\",
                                \"jobId\": \"${env.BUILD_ID}\"
                            }'"""
                        echo "Success webhook sent successfully"
                    } catch (Exception e) {
                        echo "Warning: Failed to send success webhook: ${e.message}"
                    }
                }
            }
            failure {
                echo "Deployment failed"

                script {
                    try {
                        sh """curl --location 'https://www.ydphoto.com/api/apis/${params.api_id}/webhook' \
                            --header 'Content-Type: application/json' \
                            --data '{
                                \"apiStatus\":\"ERROR\",
                                \"jobId\": \"${env.BUILD_ID}\"
                            }'"""
                        echo "Failure webhook sent successfully"
                    } catch (Exception e) {
                        echo "Error: Failed to send failure webhook: ${e.message}"
                    }
                }
            }
        }
    }
}