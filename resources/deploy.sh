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
buildpack_builder=${BUILDPACK_BUILDER:-paketobuildpacks/builder-jammy-full}
buildpack_jvm_version=${BUILDPACK_JVM_VERSION:-${BP_JVM_VERSION:-}}
buildpack_node_version=${BUILDPACK_NODE_VERSION:-${BP_NODE_VERSION:-}}
buildpack_python_version=${BUILDPACK_PYTHON_VERSION:-${BP_PYTHON_VERSION:-}}
buildpack_go_version=${BUILDPACK_GO_VERSION:-${BP_GO_VERSION:-}}

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

build_method="dockerfile"

# 查看${code_dir}中是否有Dockerfile，如果没有，默认使用 Cloud Native Buildpacks 构建镜像
if [ ! -f "${code_dir}/Dockerfile" ]; then
    echo "未找到Dockerfile，使用 Cloud Native Buildpacks 构建镜像"
    build_method="buildpacks"
else
    echo "使用项目中的Dockerfile"
fi

# 检查Docker是否运行
if ! docker info > /dev/null 2>&1; then
    echo "错误: Docker守护进程未运行"
    exit 1
fi

echo "开始构建Docker镜像..."
if [ "$build_method" = "buildpacks" ]; then
    if ! command -v pack > /dev/null 2>&1; then
        echo "错误: 未找到 pack 命令。无Dockerfile并使用 buildpacks 构建时需要安装 Cloud Native Buildpacks pack CLI"
        exit 1
    fi

    pack_args=(
        build "${project_name}"
        --path "${code_dir}"
        --builder "${buildpack_builder}"
    )

    if [ -n "$buildpack_jvm_version" ]; then
        pack_args+=(--env "BP_JVM_VERSION=${buildpack_jvm_version}")
        echo "  JVM版本: ${buildpack_jvm_version}"
    fi
    if [ -n "$buildpack_node_version" ]; then
        pack_args+=(--env "BP_NODE_VERSION=${buildpack_node_version}")
        echo "  Node.js版本: ${buildpack_node_version}"
    fi
    if [ -n "$buildpack_python_version" ]; then
        pack_args+=(--env "BP_PYTHON_VERSION=${buildpack_python_version}")
        echo "  Python版本: ${buildpack_python_version}"
    fi
    if [ -n "$buildpack_go_version" ]; then
        pack_args+=(--env "BP_GO_VERSION=${buildpack_go_version}")
        echo "  Go版本: ${buildpack_go_version}"
    fi

    echo "  构建方式: buildpacks"
    echo "  Builder: ${buildpack_builder}"
    pack "${pack_args[@]}"
else
#    echo "  构建方式: Dockerfile"
#    echo "  Dockerfile: ${code_dir}/Dockerfile"
#    docker build -t "${project_name}" \
#        --build-arg VERSION="${version}" \
#        --label "project=${project_name}" \
#        --label "build-time=$(date +%Y-%m-%dT%H:%M:%S)" \
#        "${code_dir}"
    python ci_docker.py
fi

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
