# jenkins-pipeline-shared

Jenkins Shared Library，用来封装常用 CI/CD、K3s 部署、Docker 构建、Helm 发布、MySQL 用户/库管理等流水线逻辑。

在 Jenkinsfile 中引用：

```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _
```

## K3s API 部署

重点使用这两个入口：

- `deploy_api_to_k3s`：完整 CI/CD，拉代码、构建镜像、推送镜像、再部署到 K3s。
- `deploy_api_to_k3s_cd`：只做 CD，用已有镜像 tag 部署到 K3s。

相关资源脚本：

- `resources/deploy.sh`：构建并推送 Docker 镜像。
- `resources/deploy_helm.sh`：生成临时 Helm values 文件并执行 `helm upgrade --install`。
- `resources/Dockerfile_java8`：Java 项目没有 Dockerfile 时使用的默认模板。

### deploy_api_to_k3s

适合从源码开始完成一次完整发布。

```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

deploy_api_to_k3s(
    gitUrl: 'https://git.example.com/team/demo-api.git',
    branch: 'main',
    api_name: 'demo-api',
    api_id: '123',
    envs: '{"APP_ENV":"prod","LOG_LEVEL":"info"}',
    call_back_host: 'https://console.example.com',
    helmGitUrl: 'https://github.com/jiangchengyu998/devops-learn.git',
    helmGitBranch: 'main',
    helmEnv: 'prod',
    helmHostSuffix: 'ydphoto.com'
)
```

常用参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `gitUrl` / `GIT_URL` | 是 | 应用源码仓库地址 |
| `branch` | 是 | 应用源码分支 |
| `api_name` | 是 | API 名称，也是 Helm release 名称，必须符合 Kubernetes DNS label |
| `api_id` | 否 | 回调接口使用的 API ID |
| `envs` | 否 | JSON 对象，会写入 Helm values 的顶层 `env:` |
| `gitToken` | 否 | HTTP(S) Git 仓库 token |
| `gitCredentialsId` / `GIT_CREDENTIALS_ID` | 否 | Jenkins Git 凭据 ID，优先级高于 `gitToken` |
| `call_back_host` / `CALL_BACK_HOST` | 否 | 构建结束后的 webhook 回调地址 |
| `helmGitUrl` / `HELM_GIT_URL` | 是 | Helm charts 仓库地址 |
| `helmGitBranch` / `HELM_GIT_BRANCH` | 是 | Helm charts 仓库分支 |
| `helmEnv` / `HELM_ENV` | 是 | 替换 values 中的 `ENVIRONMENT` |
| `helmHostSuffix` / `HELM_HOST_SUFFIX` | 是 | Ingress host 后缀，最终 host 为 `${api_name}.${suffix}` |
| `helmChart` / `HELM_CHART` | 否 | 指定 chart 名称；不填时 Java 用 `springboot-api`，其他用 `generic-api` |
| `exe_node` | 否 | Jenkins agent label，默认 `w-ubuntu` |

> `API_PORT` 已不再作为流水线入参使用。容器端口以 Dockerfile 中的 `EXPOSE` 为准。

### 语言和 Dockerfile 规则

当前只区分两类项目：

- Java：存在 `pom.xml`、`build.gradle` 或 `build.gradle.kts`。
- 其他：不细分 Node/Python/Go 等语言。

Dockerfile 规则：

- 项目自带 Dockerfile 时，直接使用项目内 Dockerfile。
- Java 项目没有 Dockerfile 时，会自动使用 `resources/Dockerfile_java8`。
- 非 Java 项目必须自带 Dockerfile。

Dockerfile 必须声明 `EXPOSE`，例如：

```dockerfile
EXPOSE 8080
```

也支持变量形式：

```dockerfile
ARG SERVER_PORT=8080
ENV SERVER_PORT=${SERVER_PORT}
EXPOSE ${SERVER_PORT}
```

流水线会读取最终 Dockerfile 的 `EXPOSE` 端口，并传给 Helm values。

### Helm values 替换

`deploy_helm.sh` 会基于 chart 的 `values.yaml` 生成临时 values 文件，然后部署。

默认替换这些占位符：

| 占位符 | 来源 |
| --- | --- |
| `HOSTNAME` | `${api_name}.${HELM_HOST_SUFFIX}` |
| `API-NAME` | `api_name` |
| `TAG` | 构建出的镜像 tag |
| `ENVIRONMENT` | `HELM_ENV` |

如果 Dockerfile 里读取到了 `EXPOSE` 端口，还会替换：

- `API-PORT`
- `API_PORT`
- `CONTAINER-PORT`
- `CONTAINER_PORT`
- `SERVER-PORT`
- `SERVER_PORT`

`envs` 有值时，要求是 JSON 对象：

```json
{"APP_ENV":"prod","LOG_LEVEL":"info"}
```

会写成 values 顶层 `env:`：

```yaml
env:
  - name: APP_ENV
    value: "prod"
  - name: LOG_LEVEL
    value: "info"
```

如果 values 文件已有顶层 `env:`，脚本会替换成这次传入的内容。

### deploy_api_to_k3s_cd

适合镜像已经构建好，只需要指定 tag 发布。

```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

deploy_api_to_k3s_cd(
    api_name: 'demo-api',
    api_id: '123',
    tag: '1.0.0',
    envs: '{"APP_ENV":"prod"}',
    call_back_host: 'https://console.example.com',
    helmGitUrl: 'git@github.com:jiangchengyu998/devops-learn.git',
    helmGitBranch: 'main',
    helmGitCredentialsId: 'github-ssh-key',
    helmEnv: 'prod',
    helmHostSuffix: 'ydphoto.com',
    helmChart: 'springboot-api',
    helmNamespace: 'default'
)
```

常用参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `api_name` | 是 | Helm release 名称，必须符合 Kubernetes DNS label |
| `tag` / `TAG` | 是 | 要部署的镜像 tag |
| `envs` | 否 | JSON 对象，会写入 Helm values 的顶层 `env:` |
| `helmGitUrl` / `HELM_GIT_URL` | 是 | Helm charts 仓库地址 |
| `helmGitBranch` / `HELM_GIT_BRANCH` | 是 | Helm charts 仓库分支 |
| `helmGitCredentialsId` / `HELM_GIT_CREDENTIALS_ID` | 否 | Helm 仓库 Jenkins 凭据 ID |
| `helmEnv` / `HELM_ENV` | 是 | 替换 values 中的 `ENVIRONMENT` |
| `helmHostSuffix` / `HELM_HOST_SUFFIX` | 是 | Ingress host 后缀 |
| `helmChart` / `HELM_CHART` | 是 | Helm chart 名称 |
| `helmNamespace` / `HELM_NAMESPACE` | 是 | Kubernetes namespace，默认 `default` |

纯 CD 流程不拉应用源码，所以不会读取 Dockerfile 端口；端口应由 chart values 自身或镜像对应的 chart 配置决定。

### 运行依赖

执行节点需要具备：

- `git`
- `docker`
- `helm`
- `kubectl`
- `jq`：当 `envs` 有值时用于把 JSON 转成 values YAML。

K3s 默认 kubeconfig：

```text
/etc/rancher/k3s/k3s.yaml
```

可通过 `KUBECONFIG` 环境变量覆盖。

## 其他流水线

### deploy_api

老版本 Docker 单机部署入口，使用 `resources/deploy_docker.sh`，会在目标节点上构建镜像并运行容器。

```groovy
deploy_api(
    gitUrl: 'https://git.example.com/team/demo-api.git',
    branch: 'main',
    api_name: 'demo-api',
    API_PORT: '8080',
    envs: '{"APP_ENV":"dev"}'
)
```

### delete_api

删除已有 API 部署，具体参数以 `vars/delete_api.groovy` 为准。

### add_rr

添加 RR 配置。

```groovy
add_rr(
    RR: 'demo',
    exe_node: 'w-ubuntu'
)
```

### add_nginx_file

生成或添加 Nginx 配置文件，具体参数以 `vars/add_nginx_file.groovy` 为准。

### MySQL 管理

创建用户：

```groovy
create_mysql_user(
    MYSQL_USER: 'test',
    MYSQL_PASSWORD: 'test',
    MYSQL_HOST: 'ydphoto.com:3306',
    MYSQL_ROOT_USER: 'root',
    MYSQL_ROOT_PASSWORD: 'XXXXX',
    agent: 'w-ubuntu'
)
```

创建数据库：

```groovy
create_mysql_database(
    MYSQL_USER: 'test',
    DB_NAME: 'test_db',
    MYSQL_HOST: 'ydphoto.com',
    MYSQL_ROOT_USER: 'root',
    MYSQL_ROOT_PASSWORD: 'XXXXX',
    agent: 'w-ubuntu'
)
```

删除数据库或用户：

```groovy
delete_mysql_database_and_user(
    db_name: 'my_database',
    mysql_user: 'my_user',
    mysql_root_password: 'password',
    delete_database: true,
    delete_user: true
)
```

### 通知

`sendNotifications` 用于发送构建通知，具体参数以 `vars/sendNotifications.groovy` 为准。
