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
                        sh "git clone -b ${config.branch ?: 'main'} ${params.GIT_URL}"
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        // 获取当前目录（git clone后的目录）
                        def code_dir = pwd()

                        sh "ls -l"
                        sh "pwd"
                        // 给部署脚本添加执行权限
                        // 从 shared library 的 resources 中加载 deploy.sh
                        def scriptContent = libraryResource('deploy.sh')
                        writeFile file: 'deploy.sh', text: scriptContent
                        sh 'chmod +x deploy.sh'

                        sh "./deploy.sh ${pwd()}/${repoName} ${params.API_PORT}"
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
