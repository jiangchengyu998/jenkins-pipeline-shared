#!/bin/bash

set -euo pipefail

# 参数检查
if [ $# -lt 2 ]; then
    echo "用法: $0 <代码目录> <API端口> [环境变量JSON] [项目名] [仓库用户名] [仓库密码] [镜像版本]"
    echo "示例: $0 /path/to/code 8080 '{\"DB_HOST\":\"localhost\"}' my-project '' '' 1.0.0"
    exit 1
fi

code_dir=$1
api_port=$2
envs=${3:-}
project_name=${4:-}

harbor_username=${5:-}
harbor_password=${6:-}
version=${7:-latest}
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

if ! [[ "$api_port" =~ ^[0-9]{1,5}$ ]] || [ "$api_port" -lt 1 ] || [ "$api_port" -gt 65535 ]; then
    echo "错误: API端口无效: $api_port"
    exit 1
fi

# 设置日志目录
log_dir="/var/log/${project_name}"
mkdir -p "$log_dir"

echo "项目名称: $project_name"
echo "代码目录: $code_dir"
echo "API端口: $api_port"
echo "日志目录: $log_dir"
echo "镜像版本: $version"
echo "镜像仓库: $registry"

# 检测项目语言类型
detect_language() {
    local dir=$1

    # 检查是否是Java项目
    if [ -f "$dir/pom.xml" ]; then
        echo "java"
    elif [ -f "$dir/build.gradle" ] || [ -f "$dir/build.gradle.kts" ]; then
        echo "java"
    # 检查是否是Node.js项目
    elif [ -f "$dir/package.json" ]; then
        echo "nodejs"
    # 检查是否是Python项目
    elif [ -f "$dir/requirements.txt" ] || [ -f "$dir/setup.py" ] || [ -f "$dir/pyproject.toml" ]; then
        echo "python"
    # 检查是否是Go项目
    elif [ -f "$dir/go.mod" ]; then
        echo "golang"
    else
        echo "unknown"
    fi
}

# 检测语言类型
language=$(detect_language "$code_dir")
echo "检测到项目类型: $language"

# 查看${code_dir}中是否有Dockerfile，如果没有，则根据项目语言类型进行选择Dockerfile
if [ ! -f "${code_dir}/Dockerfile" ]; then
    echo "未找到Dockerfile，根据项目语言类型使用模板Dockerfile"
    case $language in
        java)
            echo "使用Dockerfile.java模板"
            cp ./Dockerfile_java8 "${code_dir}/Dockerfile"
            ;;
        nodejs)
            if [ ! -f ./Dockerfile_nodejs ]; then
                echo "错误: 未找到Dockerfile_nodejs模板"
                exit 1
            fi
            cp ./Dockerfile_nodejs "${code_dir}/Dockerfile"
            ;;
        python)
            if [ ! -f ./Dockerfile_python ]; then
                echo "错误: 未找到Dockerfile_python模板"
                exit 1
            fi
            cp ./Dockerfile_python "${code_dir}/Dockerfile"
            ;;
        golang)
            if [ ! -f ./Dockerfile_golang ]; then
                echo "错误: 未找到Dockerfile_golang模板"
                exit 1
            fi
            cp ./Dockerfile_golang "${code_dir}/Dockerfile"
            ;;
        *)
            echo "错误: 不支持的语言类型: $language"
            exit 1
            ;;
    esac
else
    echo "使用项目中的Dockerfile"
fi

# 检查Docker是否运行
if ! docker info > /dev/null 2>&1; then
    echo "错误: Docker守护进程未运行"
    exit 1
fi

# 构建镜像
echo "开始构建Docker镜像..."
# 如果是nextjs项目，解析环境变量中是否有NEXT_PUBLIC开头的，如果有则替换Dockerfile中的NEXT_PUBLIC开头的配置
if [ "$language" = "nodejs" ]; then
    if [ -n "$envs" ] && [ "$envs" != "null" ]; then
        echo "解析环境变量..."
        # 使用jq解析JSON环境变量
        if command -v jq > /dev/null 2>&1; then
            env_vars=$(echo "$envs" | jq -r 'to_entries | map("\(.key)=\(.value)") | .[]' 2>/dev/null || echo "")
            if [ -n "$env_vars" ]; then
                while IFS= read -r line; do
                    if [ -n "$line" ]; then
                        if [[ $line == NEXT_PUBLIC* ]]; then
                            echo "  构建环境变量: ${line%=*}"
                            # 只取=前面的一段
                            dockerfile_line=$(grep -n -F "ENV ${line%=*}" "${code_dir}/Dockerfile" | head -1 | cut -d: -f1 || true)
                            # 将 $line 的 = 换为空格
                            if [ -n "$dockerfile_line" ]; then
                                replacement=$(printf 'ENV %s' "${line/=/ }" | sed -e 's/[&|]/\\&/g')
                                sed -i "${dockerfile_line}s|^.*$|${replacement}|" "${code_dir}/Dockerfile"
                            fi
                        fi
                    fi
                done <<< "$env_vars"
            fi
        else
            echo "警告: 未找到jq命令，无法解析JSON环境变量"
        fi
    fi
fi

echo "  Dockerfile: ${code_dir}/Dockerfile"

docker build -t "${project_name}" \
    --build-arg SERVER_PORT="${api_port}" \
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
