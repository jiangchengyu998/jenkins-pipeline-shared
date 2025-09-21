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