# Dockerfile
FROM jenkins/jenkins:jdk21

# 切换到 root 用户安装软件
USER root

# 安装常用工具和依赖
RUN apt-get update && \
    apt-get install -y \
    openssh-client \
    default-mysql-client \
    mariadb-client \
    wget \
    curl \
    jq \
    yq \
    git \
    vim \
    nano \
    gnupg \
    software-properties-common && \
    rm -rf /var/lib/apt/lists/*