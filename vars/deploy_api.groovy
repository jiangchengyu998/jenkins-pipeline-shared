// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu'}
        environment {
            http_proxy  = ''
            https_proxy = ''
            HTTP_PROXY  = ''
            HTTPS_PROXY = ''
        }
        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'API_PORT', defaultValue: config.apiPort ?: '3000', description: 'API port number')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'API port number')
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        // 清空当前 workspace（注意会删除所有文件）
                        deleteDir()
                    }
                }
            }
            stage('Checkout') {
                steps {
                    script {
                        // 自动提取仓库名（取 URL 最后一段去掉 .git）
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')

                        // 在指定目录中 checkout
//                        dir(repoName) {
//                            git branch: config.branch ?: 'main',url: "${params.GIT_URL}"
//                        }
//                        git branch: config.branch ?: 'main', url: "${params.GIT_URL}"
                        sh "git clone -b ${config.branch ?: 'main'} ${params.GIT_URL}"
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')
                        def code_dir = "${pwd()}/${repoName}"
                        // 给部署脚本添加执行权限
                        // 从 shared library 的 resources 中加载 deploy.sh
                        def scriptContent = libraryResource('deploy.sh')
                        writeFile file: 'deploy.sh', text: scriptContent
                        sh 'chmod +x deploy.sh'

                        sh "./deploy.sh ${code_dir} ${params.API_PORT}"
                        sh "ls -l"
                        sh "pwd"
                        sh "docker ps"
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment completed successfully on port ${params.API_PORT}"
            }
            failure {
                echo "Deployment failed"
            }
        }
    }
}
