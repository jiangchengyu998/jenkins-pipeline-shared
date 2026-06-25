#!/bin/bash

set -euo pipefail

# 参数检查
if [ $# -lt 1 ]; then
    echo "用法: $0 <代码目录> [环境变量JSON] [项目名] [仓库用户名] [仓库密码] [镜像版本]"
    echo "示例: $0 /path/to/code '{\"DB_HOST\":\"localhost\"}' my-project '' '' 1.0.0"
    exit 1
fi

code_dir=$1
envs=${2:-}
project_name=${3:-}

harbor_username=${4:-}
harbor_password=${5:-}
version=${6:-latest}
registry=${DOCKER_REGISTRY:-192.168.50.18:5000}

# 验证代码目录是否存在
if [ ! -d "$code_dir" ]; then
    echo "错误: 代码目录不存在: $code_dir"
    exit 1
fi

# 获取code_dir最后一个/后面的字符串作为项目名
if [ -z "$project_name" ]; then
    project_name=${code_dir##*/}
fi

# 清理项目名，只保留字母数字和连字符
project_name=$(echo "$project_name" | tr -cd '[:alnum:]-_' | tr '[:upper:]' '[:lower:]')
version=$(echo "$version" | tr -cd '[:alnum:]_.-' | cut -c 1-128)

if [ -z "$project_name" ]; then
    echo "错误: 项目名为空"
    exit 1
fi

if [ -z "$version" ]; then
    version="latest"
fi

# 设置日志目录
log_dir="/var/log/${project_name}"
mkdir -p "$log_dir"

echo "项目名称: $project_name"
echo "代码目录: $code_dir"
echo "日志目录: $log_dir"
echo "镜像版本: $version"
echo "镜像仓库: $registry"

# 检测项目语言类型：当前只区分 Java 和其他
detect_language() {
    local dir=$1

    if [ -f "$dir/pom.xml" ]; then
        echo "java"
    elif [ -f "$dir/build.gradle" ] || [ -f "$dir/build.gradle.kts" ]; then
        echo "java"
    else
        echo "other"
    fi
}

# 检测语言类型
language=$(detect_language "$code_dir")
echo "检测到项目类型: $language"

# 查看${code_dir}中是否有Dockerfile，如果没有，则根据项目语言类型进行选择Dockerfile
if [ ! -f "${code_dir}/Dockerfile" ]; then
    if [ "$language" = "java" ]; then
        echo "未找到Dockerfile，Java项目使用Dockerfile_java8模板"
        cp ./Dockerfile_java8 "${code_dir}/Dockerfile"
    else
        echo "错误: 非Java项目必须在项目中提供Dockerfile"
        exit 1
    fi
else
    echo "使用项目中的Dockerfile"
fi

# 检查Docker是否运行
if ! docker info > /dev/null 2>&1; then
    echo "错误: Docker守护进程未运行"
    exit 1
fi

echo "开始构建Docker镜像..."
echo "  Dockerfile: ${code_dir}/Dockerfile"

docker build -t "${project_name}" \
    --build-arg VERSION="${version}" \
    --label "project=${project_name}" \
    --label "build-time=$(date +%Y-%m-%dT%H:%M:%S)" \
    "${code_dir}"

echo "镜像构建完成: ${project_name}"

if [ "${DOCKER_REGISTRY_LOGIN:-false}" = "true" ]; then
    if [ -z "$harbor_username" ] || [ -z "$harbor_password" ]; then
        echo "错误: DOCKER_REGISTRY_LOGIN=true 时必须提供仓库用户名和密码"
        exit 1
    fi
    echo "登录Docker仓库: ${registry}"
    printf '%s' "$harbor_password" | docker login "$registry" -u "$harbor_username" --password-stdin
fi

# 给镜像打标签
image_tag="${registry}/${project_name}:$version"
docker tag "${project_name}" "${image_tag}"
echo "镜像打标签完成: ${image_tag}"
docker push "${image_tag}"
