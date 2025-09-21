// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'aliyun'}
        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'sample', description: 'sample')
            string(name: 'api_port', defaultValue: config.api_port ?: '3003', description: 'sample')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'aliyun', description: 'node')
        }

        stages {
            stage('check nginx file existence') {
                steps {
                    script {
                        def checkResult = sh(
                                script: "ls /etc/nginx/sites-enabled",
                                returnStdout: true
                        )

                        echo "checkResult: ${checkResult}"
                        if (checkResult.contains("${params.api_name}.conf")) {
                            echo "nginx file ${params.api_name}.conf already exists. Exiting..."
                            // 如果存在，则结束流程
                            currentBuild.result = 'SUCCESS'
                            return
                        } else {
                            echo "nginx file ${params.api_name}.conf does not exist. Adding it now..."
                            // 如果不存在，则添加nginx文件
                            echo  "add nginx file ${params.api_name}.conf"
                            echo """
                                    server {
                                        listen 443 ssl http2;
                                        listen [::]:443 ssl http2;
                                        server_name ${params.api_name}.ydphoto.com; # 关键：匹配DERP子域名
                                    
                                        # 使用 DERP 子域名的专属证书
                                        ssl_certificate /etc/ssl/ydphoto.com/fullchain.pem;
                                        ssl_certificate_key /etc/ssl/ydphoto.com/privkey.pem;
                                    
                                        location / {
                                            proxy_pass http://100.95.91.54:${params.api_port}; # 转发给DERP服务
                                            proxy_set_header Host $host;
                                            proxy_set_header X-Real-IP $remote_addr;
                                            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                                            proxy_set_header X-Forwarded-Proto $scheme;
                                    
                                            # 这些是 WebSocket 和 HTTP2 协议支持的重要设置，DERP 需要它们
                                            proxy_http_version 1.1;
                                            proxy_set_header Upgrade $http_upgrade;
                                            proxy_set_header Connection "Upgrade";
                                        }
                                    }
                            """ > "/etc/nginx/sites-enabled/${params.api_name}.conf"
                        }
                    }
                }
            }
            stage('restart nginx') {
                steps {
                    script {
                        sh "nginx -t"
//                        sh "sudo systemctl restart nginx"
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
