// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent any

        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'API_PORT', defaultValue: config.apiPort ?: '3000', description: 'API port number')
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
//                        git branch: config.branch ?: 'main', url: "${params.GIT_URL}"

                        def branch = config.branch ?: 'main'
                        def gitUrl = params.GIT_URL
                        def repoName = gitUrl.tokenize('/').last().replace('.git', '')

                        // 删除旧目录
                        sh "rm -rf ${repoName} || true"

                        // 克隆代码
                        sh "git clone -b ${branch} ${gitUrl} ${repoName}"

                        // 确保是最新代码
                        dir("${repoName}") {
                            sh "git fetch --all"
                            sh "git reset --hard origin/${branch}"
                            sh "git pull origin ${branch}"
                        }
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
