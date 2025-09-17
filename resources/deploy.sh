#!/bin/bash

code_dir=$1
api_port=$2

# 获取code_dir 最后一个/后面的字符串作为项目名
project_name=${code_dir##*/}

# 检测项目语言类型
#detect_language() {
#    local dir=$1
#
#    # 检查是否是Java项目
#    if [ -f "$dir/pom.xml" ]; then
#        echo "java"
#    elif [ -f "$dir/build.gradle" ] || [ -f "$dir/build.gradle.kts" ]; then
#        echo "java"
#    # 检查是否是Node.js项目
#    elif [ -f "$dir/package.json" ]; then
#        echo "nodejs"
#    # 检查是否是Python项目
#    elif [ -f "$dir/requirements.txt" ] || [ -f "$dir/setup.py" ] || [ -f "$dir/pyproject.toml" ]; then
#        echo "python"
#    # 检查是否是Go项目
#    elif [ -f "$dir/go.mod" ]; then
#        echo "golang"
#    else
#        echo "unknown"
#    fi
#}

# 检测语言类型
#language=$(detect_language "$code_dir")
#echo "检测到项目类型: $language"

# 制作镜像
docker build   --build-arg http_proxy=http://100.95.91.54:7890 \
               --build-arg https_proxy=http://100.95.91.54:7890 \
               -t ${project_name} --build-arg SERVER_PORT=${api_port} ${code_dir}

# 停止并删除旧的容器
docker stop ${project_name} || true
docker rm -f ${project_name} || true

# 根据语言类型决定启动方式
#if [ "$language" = "java" ]; then
#    # Java项目：设置SERVER_PORT环境变量，让Spring Boot使用指定端口
#    echo "Java项目检测到，设置内部服务端口为: $api_port"
#    docker run -d -p ${api_port}:${api_port}  --name ${project_name} ${project_name}
#else
#    # 其他语言项目：直接使用api_port作为内外端口
#    docker run -d -p ${api_port}:${api_port} --name ${project_name} ${project_name}
#fi

docker run -d -p ${api_port}:${api_port} --name ${project_name} ${project_name}

if [ $? -eq 0 ]; then
  echo "部署成功:${project_name},port: ${api_port}!"
else
  echo "部署失败:${project_name},port: ${api_port}!"
  exit 1
fi
