// vars/create_mysql_database.groovy
def call(Map config = [:]) {
    pipeline {
        agent {
            label config.agent ?: 'w-ubuntu' // 指定执行节点
        }

        parameters {
            string(name: 'DB_NAME', defaultValue: config.db_name ?: '', description: '要创建的数据库名称')
            string(name: 'MYSQL_USER', defaultValue: config.mysql_user ?: '', description: '要授予权限的MySQL用户名')
            string(name: 'MYSQL_HOST', defaultValue: config.mysql_host ?: 'localhost', description: 'MySQL服务器地址')
            string(name: 'MYSQL_ROOT_USER', defaultValue: config.mysql_root_user ?: 'root', description: 'MySQL管理员用户名')
            string(name: 'MYSQL_ROOT_PASSWORD', defaultValue: config.mysql_root_password ?: '', description: 'MySQL管理员密码', trim: true)
        }

        stages {
            stage('验证输入参数') {
                steps {
                    script {
                        if (!params.DB_NAME?.trim()) {
                            error "数据库名称不能为空"
                        }
                        if (!params.MYSQL_USER?.trim()) {
                            error "MySQL用户名不能为空"
                        }
                        if (!params.MYSQL_ROOT_PASSWORD?.trim()) {
                            error "MySQL root密码不能为空"
                        }

                        echo "参数验证通过"
                        echo "将创建数据库: ${params.DB_NAME}"
                        echo "将授予权限给用户: ${params.MYSQL_USER}"
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

            stage('检查数据库是否已存在') {
                steps {
                    script {
                        def dbExists = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e 'SHOW DATABASES LIKE \"${params.DB_NAME}\"' | grep -o \"${params.DB_NAME}\"",
                                returnStatus: true
                        )

                        if (dbExists == 0) {
                            error "数据库 ${params.DB_NAME} 已存在"
                        }

                        echo "数据库 ${params.DB_NAME} 不存在，可以创建"
                    }
                }
            }

            stage('检查用户是否存在') {
                steps {
                    script {
                        def userExists = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SELECT User FROM mysql.user WHERE User = '${params.MYSQL_USER}'\" | grep -o \"${params.MYSQL_USER}\"",
                                returnStatus: true
                        )

                        if (userExists != 0) {
                            error "MySQL用户 ${params.MYSQL_USER} 不存在，请先创建用户"
                        }

                        echo "MySQL用户 ${params.MYSQL_USER} 存在，可以授予权限"
                    }
                }
            }

            stage('创建数据库并授予权限') {
                steps {
                    script {
                        def sqlCommands = """
-- 创建数据库
CREATE DATABASE ${params.DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 授予用户对特定数据库的所有权限
GRANT ALL PRIVILEGES ON ${params.DB_NAME}.* TO '${params.MYSQL_USER}'@'%';

-- 刷新权限使更改生效
FLUSH PRIVILEGES;
"""

                        writeFile file: 'create_db.sql', text: sqlCommands

                        sh "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' < create_db.sql"

                        echo "数据库创建和权限授予成功"
                    }
                }
            }

            stage('验证创建结果') {
                steps {
                    script {
                        def dbCheck = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e 'SHOW DATABASES LIKE \"${params.DB_NAME}\"' | grep -o \"${params.DB_NAME}\"",
                                returnStatus: true
                        )

                        if (dbCheck != 0) {
                            error "数据库创建验证失败"
                        }

                        // 验证权限是否授予成功
                        def privCheck = sh(
                                script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SHOW GRANTS FOR '${params.MYSQL_USER}'@'%'\" | grep -o \"${params.DB_NAME}\"",
                                returnStatus: true
                        )

                        if (privCheck != 0) {
                            error "权限授予验证失败"
                        }

                        echo "数据库创建和权限授予验证成功"
                    }
                }
            }
        }

        post {
            success {
                script {
                    sh 'rm -f create_db.sql'

                    echo """
==============================================
MySQL 数据库创建成功!
==============================================
数据库名称: ${params.DB_NAME}
授权用户: ${params.MYSQL_USER}
主机: ${params.MYSQL_HOST}

连接示例:
mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_USER} -p <密码> ${params.DB_NAME}
==============================================
"""
                }
            }
            failure {
                script {
                    sh 'rm -f create_db.sql'
                    echo "操作失败，已清理临时文件"
                }
            }
            always {
                echo "MySQL数据库创建流程结束"
            }
        }
    }
}