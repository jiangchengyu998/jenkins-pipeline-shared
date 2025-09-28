import groovy.json.JsonSlurper


// vars/delete_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label 'w-ubuntu' }
        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'demo', description: 'API 名称')
            string(name: 'RR', defaultValue: config.rr ?: 'demo', description: 'RR 记录')
        }
        stages {
            stage('删除 RR 记录') {
                steps {
                    script {
                        script {
                            def query = sh(
                                    script: "aliyun alidns DescribeDomainRecords --region public --DomainName 'ydphoto.com' --RRKeyWord ${params.RR}",
                                    returnStdout: true
                            ).trim()

                            echo "DescribeDomainRecords 输出: ${query}"

                            def json = new groovy.json.JsonSlurper().parseText(query) as Map  // 强转成普通 Map
                            def records = json.DomainRecords?.Record

                            if (records && records.size() > 0) {
                                def id = records[0].RecordId
                                sh "aliyun alidns DeleteDomainRecord --region public --RecordId ${id}"
                                echo "RR 记录已删除"
                            } else {
                                echo "未找到 RR 记录，无需删除"
                            }
                        }

                    }
                }
            }

            stage('删除 nginx 配置') {
                agent { label 'aliyun' }
                steps {
                    script {
                        def confExists = sh(
                                script: "sudo test -f /etc/nginx/sites-enabled/${params.api_name}.conf",
                                returnStatus: true
                        )
                        if (confExists != 0) {
                            echo "未找到 nginx 配置文件，无需删除"
                        } else {
                            sh "sudo rm -f /etc/nginx/sites-enabled/${params.api_name}.conf"
                            sh "sudo nginx -t && sudo systemctl reload nginx"
                            echo "nginx 配置已删除并重载"
                        }
                    }
                }
            }

            stage('停止并删除 API 服务') {
                agent { label 'w-ubuntu' }
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
