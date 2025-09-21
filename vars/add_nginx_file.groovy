// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'aliyun'}
        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'sample', description: 'sample')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'aliyun', description: 'node')
        }

        stages {
            stage('check nginx file existence') {
                steps {
                    script {
                        def checkResult = sh(
                                script: "ls",
                                returnStdout: true
                        )

                        echo "checkResult: ${checkResult}"

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
