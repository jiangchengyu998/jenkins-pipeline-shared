// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu'}
        parameters {
            string(name: 'RR', defaultValue: config.rr ?: 'yy', description: 'rr')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'node')
        }


        stages {
            stage('list RR') {
                steps {
                    script {
                        sh "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com'"
                    }
                }
            }
            stage('add RR') {
                steps {
                    script {
                        sh "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com' --RRKeyWord ${params.RR}"
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
