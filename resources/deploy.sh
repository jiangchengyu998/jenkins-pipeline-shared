#!/bin/bash

code_dir=$1
api_port=$2

# 获取code_dir 最后一个/后面的字符串作为项目名
project_name=${code_dir##*/}

# 制作镜像
docker build -t ${project_name} ${code_dir}
# 停止并删除旧的容器
docker stop ${project_name} || true
# 删除旧容器
docker rm -f ${project_name} || true
# 启动容器
docker run -d -p ${api_port}:${api_port} --name ${project_name} ${project_name}
if [ $? -eq 0 ]; then
  echo "部署成功:${project_name},port: ${api_port}!"
else
  echo "部署失败:${project_name},port: ${api_port}!"
  exit 1
fi
echo "部署成功:${project_name},port: ${api_port}!"