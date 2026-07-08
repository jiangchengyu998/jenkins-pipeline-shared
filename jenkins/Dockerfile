# Dockerfile
FROM jenkins/jenkins:jdk21

ARG PACK_VERSION=0.40.7

# 切换到 root 用户安装软件
USER root

RUN mkdir -p /root/.ssh && \
    chmod 700 /root/.ssh

COPY ssh_config /root/.ssh/config

RUN chmod 600 /root/.ssh/config

# 安装常用工具和依赖
RUN apt-get update && \
    apt-get install -y \
    openssh-client \
    default-mysql-client \
    mariadb-client \
    ca-certificates \
    wget \
    curl \
    jq \
    yq \
    git \
    vim \
    nano \
    gnupg && \
    rm -rf /var/lib/apt/lists/*

# 安装 Cloud Native Buildpacks pack CLI
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) pack_arch="linux" ;; \
        arm64) pack_arch="linux-arm64" ;; \
        *) echo "Unsupported architecture for pack CLI: $arch" >&2; exit 1 ;; \
    esac; \
    curl -fsSL \
        "https://github.com/buildpacks/pack/releases/download/v${PACK_VERSION}/pack-v${PACK_VERSION}-${pack_arch}.tgz" \
        -o /tmp/pack.tgz; \
    tar -xzf /tmp/pack.tgz -C /usr/local/bin pack; \
    chmod +x /usr/local/bin/pack; \
    rm -f /tmp/pack.tgz; \
    pack version
