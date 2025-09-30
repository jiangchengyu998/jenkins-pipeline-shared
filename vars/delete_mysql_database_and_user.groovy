// vars/delete_mysql_database_and_user.groovy
def call(Map config = [:]) {
    pipeline {
        agent {
            label config.agent ?: 'w-ubuntu' // 指定执行节点
        }

        parameters {
            booleanParam(name: 'DELETE_DATABASE', defaultValue: config.delete_database ?: true, description: '是否删除数据库')
            booleanParam(name: 'DELETE_USER', defaultValue: config.delete_user ?: false, description: '是否删除用户')
            string(name: 'DB_NAME', defaultValue: config.db_name ?: '', description: '要删除的数据库名称')
            string(name: 'MYSQL_USER', defaultValue: config.mysql_user ?: '', description: '要删除的MySQL用户名')
            string(name: 'MYSQL_HOST', defaultValue: config.mysql_host ?: 'ydphoto.com', description: 'MySQL服务器地址')
            string(name: 'MYSQL_ROOT_USER', defaultValue: config.mysql_root_user ?: 'root', description: 'MySQL管理员用户名')
            string(name: 'MYSQL_ROOT_PASSWORD', defaultValue: config.mysql_root_password ?: '', description: 'MySQL管理员密码', trim: true)
        }

        stages {
            stage('验证输入参数') {
                steps {
                    script {
                        if (!params.DELETE_DATABASE && !params.DELETE_USER) {
                            error "必须选择至少一项操作：删除数据库或删除用户"
                        }

                        if (params.DELETE_DATABASE && !params.DB_NAME?.trim()) {
                            error "数据库名称不能为空"
                        }

                        if (params.DELETE_USER && !params.MYSQL_USER?.trim()) {
                            error "MySQL用户名不能为空"
                        }

                        if (!params.MYSQL_ROOT_PASSWORD?.trim()) {
                            error "MySQL root密码不能为空"
                        }

                        echo "参数验证通过"
                        if (params.DELETE_DATABASE) {
                            echo "将删除数据库: ${params.DB_NAME}"
                        }
                        if (params.DELETE_USER) {
                            echo "将删除用户: ${params.MYSQL_USER}"
                        }
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

            stage('检查数据库和用户状态') {
                steps {
                    script {
                        // 检查数据库是否存在
                        if (params.DELETE_DATABASE) {
                            def dbExists = sh(
                                    script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e 'SHOW DATABASES LIKE \"${params.DB_NAME}\"' | grep -o \"${params.DB_NAME}\"",
                                    returnStatus: true
                            )

                            if (dbExists != 0) {
                                echo "警告: 数据库 ${params.DB_NAME} 不存在"
                                env.DB_EXISTS = 'false'
                            } else {
                                echo "数据库 ${params.DB_NAME} 存在"
                                env.DB_EXISTS = 'true'
                            }
                        }

                        // 检查用户是否存在
                        if (params.DELETE_USER) {
                            def userExists = sh(
                                    script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SELECT User FROM mysql.user WHERE User = '${params.MYSQL_USER}'\" | grep -o \"${params.MYSQL_USER}\"",
                                    returnStatus: true
                            )

                            if (userExists != 0) {
                                echo "警告: MySQL用户 ${params.MYSQL_USER} 不存在"
                                env.USER_EXISTS = 'false'
                            } else {
                                echo "MySQL用户 ${params.MYSQL_USER} 存在"
                                env.USER_EXISTS = 'true'
                            }
                        }
                    }
                }
            }

            stage('执行删除操作') {
                steps {
                    script {
                        def sqlCommands = ""

                        // 撤销权限（如果删除数据库）
                        if (params.DELETE_DATABASE && env.DB_EXISTS == 'true') {
                            sqlCommands += """
-- 撤销用户对数据库的所有权限
REVOKE ALL PRIVILEGES ON ${params.DB_NAME}.* FROM '${params.MYSQL_USER}'@'%';
"""
                        }

                        // 删除数据库
                        if (params.DELETE_DATABASE && env.DB_EXISTS == 'true') {
                            sqlCommands += """
-- 删除数据库
DROP DATABASE IF EXISTS ${params.DB_NAME};
"""
                        }

                        // 删除用户
                        if (params.DELETE_USER && env.USER_EXISTS == 'true') {
                            sqlCommands += """
-- 删除用户
DROP USER IF EXISTS '${params.MYSQL_USER}'@'%';
"""
                        }

                        // 刷新权限
                        if (sqlCommands.trim()) {
                            sqlCommands += """
-- 刷新权限使更改生效
FLUSH PRIVILEGES;
"""

                            writeFile file: 'delete_db_and_user.sql', text: sqlCommands

                            sh "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' < delete_db_and_user.sql"

                            echo "删除操作执行完成"
                        } else {
                            echo "没有需要执行的操作"
                        }
                    }
                }
            }

            stage('验证删除结果') {
                steps {
                    script {
                        // 验证数据库是否已删除
                        if (params.DELETE_DATABASE && env.DB_EXISTS == 'true') {
                            def dbCheck = sh(
                                    script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e 'SHOW DATABASES LIKE \"${params.DB_NAME}\"' | grep -o \"${params.DB_NAME}\"",
                                    returnStatus: true
                            )

                            if (dbCheck == 0) {
                                error "数据库删除验证失败，数据库仍然存在"
                            }

                            echo "数据库删除验证成功"
                        }

                        // 验证用户是否已删除
                        if (params.DELETE_USER && env.USER_EXISTS == 'true') {
                            def userCheck = sh(
                                    script: "mysql -h ${params.MYSQL_HOST} -u ${params.MYSQL_ROOT_USER} -p'${params.MYSQL_ROOT_PASSWORD}' -e \"SELECT User FROM mysql.user WHERE User = '${params.MYSQL_USER}'\" | grep -o \"${params.MYSQL_USER}\"",
                                    returnStatus: true
                            )

                            if (userCheck == 0) {
                                error "用户删除验证失败，用户仍然存在"
                            }

                            echo "用户删除验证成功"
                        }

                        echo "所有删除操作验证成功"
                    }
                }
            }
        }

        post {
            success {
                script {
                    sh 'rm -f delete_db_and_user.sql'

                    def summary = """
==============================================
MySQL 删除操作完成!
==============================================
"""
                    if (params.DELETE_DATABASE) {
                        summary += "数据库: ${params.DB_NAME} - ${env.DB_EXISTS == 'true' ? '已删除' : '不存在'}\n"
                    }
                    if (params.DELETE_USER) {
                        summary += "用户: ${params.MYSQL_USER} - ${env.USER_EXISTS == 'true' ? '已删除' : '不存在'}\n"
                    }
                    summary += "主机: ${params.MYSQL_HOST}"
                    summary += "\n=============================================="

                    echo summary
                }
            }
            failure {
                script {
                    sh 'rm -f delete_db_and_user.sql'
                    echo "操作失败，已清理临时文件"
                }
            }
            always {
                echo "MySQL删除流程结束"
            }
        }
    }
}