// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent any

        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'API_PORT', defaultValue: config.apiPort ?: '3000', description: 'API port number')
        }

        stages {
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
//                        sh "chmod +x deploy.sh"

                        // 执行部署脚本，传入代码目录
//                        sh "./deploy.sh ${code_dir}"
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
