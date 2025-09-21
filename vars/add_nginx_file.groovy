// vars/add_nginx_file.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'aliyun'}
        parameters {
            string(name: 'api_name', defaultValue: config.api_name ?: 'sample', description: 'API name')
            string(name: 'api_port', defaultValue: config.api_port ?: '3003', description: 'API port')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'aliyun', description: 'Execution node')
            string(name: 'server_ip', defaultValue: config.server_ip ?: '100.95.91.54', description: 'Server IP address')
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
                        }
                    }
                }
            }

            stage('create nginx config') {
                steps {
                    script {
                        // 创建Nginx配置文件
                        def nginxConfig = """
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${params.api_name}.ydphoto.com; # 使用参数化的API名称

    # 使用 DERP 子域名的专属证书
    ssl_certificate /etc/ssl/ydphoto.com/fullchain.pem;
    ssl_certificate_key /etc/ssl/ydphoto.com/privkey.pem;

    location / {
        proxy_pass http://${params.server_ip}:${params.api_port}; # 使用参数化的IP和端口
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        # 这些是 WebSocket 和 HTTP2 协议支持的重要设置，DERP 需要它们
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "Upgrade";
    }
}
"""

                        // 将配置写入文件
                        writeFile file: "${params.api_name}.conf", text: nginxConfig

                        // 使用sudo权限将文件移动到nginx配置目录
                        sh "sudo mv ${params.api_name}.conf /etc/nginx/sites-enabled/"
                        sh "sudo chown root:root /etc/nginx/sites-enabled/${params.api_name}.conf"
                        sh "sudo chmod 644 /etc/nginx/sites-enabled/${params.api_name}.conf"

                        echo "Nginx configuration file created successfully"
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
                            echo "Nginx restarted successfully"
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
                // 可选：清理可能创建的文件
                sh "sudo rm -f /etc/nginx/sites-enabled/${params.api_name}.conf"
            }
        }
    }
}