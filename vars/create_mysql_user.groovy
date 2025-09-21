// vars/create_mysql_user.groovy
def call(Map config = [:]) {
    pipeline {
        agent {
            label config.agent ?: 'w-ubuntu' // 指定执行节点
        }

        parameters {
            string(name: 'MYSQL_USER', defaultValue: config.mysql_user ?: '', description: '要创建的MySQL用户名')
            string(name: 'MYSQL_PASSWORD', defaultValue: config.mysql_password ?: '', description: 'MySQL用户的密码', trim: true)
            string(name: 'MYSQL_HOST', defaultValue: config.mysql_host ?: 'localhost', description: 'MySQL服务器地址')
            string(name: 'MYSQL_ROOT_USER', defaultValue: config.mysql_root_user ?: 'root', description: 'MySQL管理员用户名')
            string(name: 'MYSQL_ROOT_PASSWORD', defaultValue: config.mysql_root_password ?: '', description: 'MySQL管理员密码', trim: true)
        }

        stages {
            stage('验证输入参数') {
                steps {
                    script {
                        if (!params.MYSQL_USER?.trim()) {
                            error "MySQL用户名不能为空"
                        }
                        if (!params.MYSQL_PASSWORD?.trim()) {
                            error "MySQL用户密码不能为空"
                        }
                        if (!params.MYSQL_ROOT_PASSWORD?.trim()) {
                            error "MySQL root密码不能为空"
                        }

                        echo "参数验证通过"
                        echo "将创建用户: ${params.MYSQL_USER}"
                    }
                }
            }

            stage('检查MySQL连接') {
                steps {
                    script {
                        def mysqlCheck = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e 'SELECT 1'",
                                returnStatus: true
                        )

                        if (mysqlCheck != 0) {
                            error "无法连接到MySQL服务器，请检查root凭据和服务器可达性"
                        }

                        echo "MySQL连接测试成功"
                    }
                }
            }

            stage('检查用户是否已存在') {
                steps {
                    script {
                        def userExists = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SELECT User FROM mysql.user WHERE User = '${params.MYSQL_USER}'\" | grep -o \"${params.MYSQL_USER}\"",
                                returnStatus: true
                        )

                        if (userExists == 0) {
                            echo "MySQL用户 ${params.MYSQL_USER} 已存在"
                            // 设置标志位，但继续执行后续步骤
                            USER_ALREADY_EXISTS = true
                        } else {
                            echo "MySQL用户 ${params.MYSQL_USER} 不存在，将创建用户"
                            USER_ALREADY_EXISTS = false
                        }
                    }
                }
            }

            stage('创建用户') {
                steps {
                    script {
                        // 只有当用户不存在时才执行创建操作
                        if (!USER_ALREADY_EXISTS) {
                            def sqlCommands = """
-- 创建用户并设置密码
CREATE USER '${params.MYSQL_USER}'@'%' IDENTIFIED BY '${params.MYSQL_PASSWORD}';

-- 刷新权限使更改生效
FLUSH PRIVILEGES;
"""

                            writeFile file: 'create_user.sql', text: sqlCommands

                            sh "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' < create_user.sql"

                            echo "用户创建成功"
                        } else {
                            echo "用户已存在，跳过创建步骤"
                        }
                    }
                }
            }

            stage('验证用户创建') {
                steps {
                    script {
                        // 无论用户是否已存在，都执行验证步骤
                        def userCheck = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SELECT User FROM mysql.user WHERE User = '${params.MYSQL_USER}'\" | grep -o \"${params.MYSQL_USER}\"",
                                returnStatus: true
                        )

                        if (userCheck != 0) {
                            error "用户验证失败"
                        }

                        echo "用户验证成功"
                    }
                }
            }
        }

        post {
            success {
                script {
                    sh 'rm -f create_user.sql'

                    echo """
==============================================
MySQL 用户处理成功!
==============================================
用户名: ${params.MYSQL_USER}
主机: ${params.MYSQL_HOST}
状态: ${USER_ALREADY_EXISTS ? '已存在' : '已创建'}
==============================================
"""
                }
            }
            failure {
                script {
                    sh 'rm -f create_user.sql'
                    echo "用户处理失败，已清理临时文件"
                }
            }
            always {
                echo "MySQL用户处理流程结束"
            }
        }
    }
}
