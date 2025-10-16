# jenkins-pipeline-shared

```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

add_rr([
    RR: 'yy',
    exe_node: ""
])
```
```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

create_mysql_user([
        MYSQL_USER: 'test',
        MYSQL_PASSWORD: "test",
        MYSQL_HOST: "ydphoto.com:3306",
        "MYSQL_ROOT_USER": "root",
        "MYSQL_ROOT_PASSWORD": "XXXXX",
        "agent": "w-ubuntu"
])
```

```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

create_mysql_database([
        MYSQL_USER: 'test',
        DB_NAME: "test_db",
        MYSQL_HOST: "ydphoto.com",
        "MYSQL_ROOT_USER": "root",
        "MYSQL_ROOT_PASSWORD": "XXXXX",
        "agent": "w-ubuntu"
])
```

```groovy
// 只删除数据库
delete_mysql_database_and_user(
    db_name: 'my_database',
    mysql_root_password: 'password',
    delete_database: true,
    delete_user: false
)

// 只删除用户
delete_mysql_database_and_user(
    mysql_user: 'my_user',
    mysql_root_password: 'password',
    delete_database: false,
    delete_user: true
)

// 同时删除数据库和用户
delete_mysql_database_and_user(
    db_name: 'my_database',
    mysql_user: 'my_user',
    mysql_root_password: 'password',
    delete_database: true,
    delete_user: true
)

```