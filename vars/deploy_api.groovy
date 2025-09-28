// vars/deploy_api.groovy
def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu'}
        environment {
            http_proxy  = ''
            https_proxy = ''
            HTTP_PROXY  = ''
            HTTPS_PROXY = ''
        }
        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'API_PORT', defaultValue: config.apiPort ?: '3000', description: 'API port number')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'API port number')
            string(name: 'branch', defaultValue: config.branch ?: 'main', description: 'Git branch')
            string(name: 'envs', defaultValue: config.envs ?: '', description: 'Environment variables')
            string(name: 'api_id', defaultValue: config.api_id ?: '', description: 'api_id')
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        // 清空当前 workspace（注意会删除所有文件）
                        deleteDir()
                    }
                }
            }
            stage('Checkout') {
                steps {
                    script {
                        // 自动提取仓库名（取 URL 最后一段去掉 .git）
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')

                        // 在指定目录中 checkout
//                        dir(repoName) {
//                            git branch: config.branch ?: 'main',url: "${params.GIT_URL}"
//                        }
//                        git branch: config.branch ?: 'main', url: "${params.GIT_URL}"
                        sh "git clone -b ${config.branch ?: 'main'} ${params.GIT_URL}"
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        def repoName = params.GIT_URL.tokenize('/').last().replace('.git', '')
                        def code_dir = "${pwd()}/${repoName}"
                        // 给部署脚本添加执行权限
                        // 从 shared library 的 resources 中加载 deploy.sh
                        def scriptContent = libraryResource('deploy.sh')
                        writeFile file: 'deploy.sh', text: scriptContent
                        sh 'chmod +x deploy.sh'

                        sh "./deploy.sh ${code_dir} ${params.API_PORT} '${params.envs}'"
                        sh "ls -l"
                        sh "pwd"
                        sh "docker ps"
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment api ${params.api_id} completed successfully on port ${params.API_PORT}"
                // 调用接口更新api的状态
                //curl --location 'http://192.168.101.60:3000/api/apis/cmg3myjoa000aqj012vnvf3ae/webhook' \
                //--header 'Content-Type: application/json' \
                //--data '{
                //    "apiStatus":"RUNNING",
                //    "jobId": "35"
                //}'
//                sh """curl --location 'http://192.168.101.60:3000/api/apis/${params.api_id}/webhook' \
                sh """curl --location 'https://www.ydphoto.com/api/apis/${params.api_id}/webhook' \
                    --header 'Content-Type: application/json' \
                    --data '{
                        \"apiStatus\":\"RUNNING\",
                        \"jobId\": \"${env.BUILD_ID}\"
                    }'"""

                // 发送邮件给jchengyu0829@163.com

//                mail to: 'jchengyu0829@163.com',
//                     subject: "API ${params.api_id} Deployed Successfully",
//                     body: """<p>API with ID: ${params.api_id} has been successfully deployed.</p>
//                             <p>Access it at: <a href="https://${params.api_id}.ydphoto.com">https://${params.api_id}.ydphoto.com</a></p>
//                             <p>Deployed by Jenkins Job: ${env.BUILD_URL}</p>""",
////                             <p>Deployed by Jenkins Job: ${env.BUILD_URL}</p>""",
//                     mimeType: 'text/html'

            }
            failure {
                echo "Deployment failed"
                sh """curl --location 'http://192.168.101.60:3000/api/apis/${params.api_id}/webhook' \
                    --header 'Content-Type: application/json' \
                    --data '{
                        \"apiStatus\":\"ERROR\",
                        \"jobId\": \"${env.BUILD_ID}\"
                    }'"""
            }
        }
    }
}
