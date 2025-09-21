// vars/add_nginx_file.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'aliyun'}
        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'sample', description: 'API name')
            string(name: 'api_port', defaultValue: config.api_port ?: '3003', description: 'API port')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'aliyun', description: 'Execution node')
        }

        stages {
            stage('check nginx file existence') {
                steps {
                    script {
                        // 获取目录内容列表
                        def checkResult = sh(
                                script: "ls /etc/nginx/sites-enabled/",
                                returnStdout: true,
                                returnStatus: false
                        ).trim()

                        echo "Existing files: ${checkResult}"

                        if (checkResult.contains("${params.api_name}.conf")) {
                            echo "nginx file ${params.api_name}.conf already exists. Exiting..."
                            // 如果存在，则结束流程
                            currentBuild.result = 'SUCCESS'
                            return
                        } else {
                            echo "nginx file ${params.api_name}.conf does not exist. Adding it now..."
                            // 如果不存在，则添加nginx文件
                            sh '''
                                sudo tee /etc/nginx/sites-enabled/${params.api_name}.conf > /dev/null <<EOF
server {

}
                                EOF
                            '''
                        }
                    }
                }
            }
            stage('restart nginx') {
                steps {
                    script {
                        def testResult = sh(
                                script: "sudo nginx -t",
                                returnStatus: true
                        )
                        if (testResult == 0) {
                            sh "sudo systemctl restart nginx"
                        } else {
                            error "Nginx configuration test failed"
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Nginx configuration added and service restarted successfully"
            }
            failure {
                echo "Operation failed"
            }
        }
    }
}
