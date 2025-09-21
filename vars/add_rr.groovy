class add_rr {
    call(Map config = [:]) {
        pipeline {
            agent { label config.exe_node ?: 'w-ubuntu' }
            parameters {
                string(name: 'RR', defaultValue: config.rr ?: 'yy', description: 'RR repository URL')
            }
            stages {
                stage('list RR') {
                    steps {
                        script {
                            sh "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com'"
                        }
                    }
                }
            }
            stages {
                stage('add RR') {
                    steps {
                        script {
                            sh "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com' --RRKeyWord ${params.RR}"
                        }
                    }
                }
            }
        }
    }
}
