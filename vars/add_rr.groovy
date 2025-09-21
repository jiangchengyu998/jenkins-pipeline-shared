// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu'}
        parameters {
            string(name: 'RR', defaultValue: config.rr ?: 'yy', description: 'rr')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'node')
        }

        stages {
            stage('check RR existence') {
                steps {
                    script {
                        def checkResult = sh(
                                script: "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com' --RRKeyWord ${params.RR}",
                                returnStdout: true
                        )

                        // 检查返回结果中是否包含指定的RR记录
                        if (checkResult.contains('"TotalCount": 0')) {
                            echo "RR record ${params.RR} does not exist. Adding it now..."
                            // 如果不存在，则添加RR记录
                            sh "aliyun alidns AddDomainRecord --region public --DomainName 'ydphoto.com' --RR ${params.RR} --Type A --Value '8.138.212.208'"
                        } else {
                            echo "RR record ${params.RR} already exists. Exiting..."
                            // 如果存在，则结束流程
                            currentBuild.result = 'SUCCESS'
                            return
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Operation completed successfully"
            }
            failure {
                echo "Operation failed"
            }
        }
    }
}
