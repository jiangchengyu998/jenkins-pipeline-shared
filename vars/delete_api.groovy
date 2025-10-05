// vars/delete_api.groovy

def call(Map config = [:]) {
    pipeline {
        agent { label 'w-ubuntu' }

        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'demo', description: 'API 名称')
            string(name: 'RR', defaultValue: config.rr ?: 'demo', description: 'RR 记录')
        }

        stages {

            stage('停止并删除 API 服务') {
                steps {
                    script {
                        sh "docker stop ${params.api_name} || true"
                        sh "docker rm ${params.api_name} || true"
                        echo "API 服务已停止并删除"
                    }
                }
            }

        }

        post {
            success {
                echo "API 删除流程完成"
            }
            failure {
                echo "API 删除流程失败"
            }
        }
    }
}
