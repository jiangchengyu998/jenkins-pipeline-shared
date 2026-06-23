#!/bin/sh
set -eu

# deploy_helm.sh - 部署 Helm Chart 的脚本（POSIX-sh 兼容）
# 用法（位置参数）：
#   deploy_helm.sh <chart_path> <hostname> <api_name> <tag> <environment> [namespace]
# 例如：
#   ./resources/deploy_helm.sh ../charts/springboot-api api.example.com demo-api 1.0.0 prod default

check_helm() {
  if ! command -v helm >/dev/null 2>&1; then
    echo "Helm 未安装，请先安装 Helm" >&2
    exit 1
  fi
}

check_kubectl() {
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "kubectl 未安装，请先安装 kubectl" >&2
    exit 1
  fi
}

usage() {
  cat <<EOF
用法: $0 <chart_path> <hostname> <api_name> <tag> <environment> [namespace]

示例:
  $0 ../charts/springboot-api api.example.com demo-api 1.0.0 prod default

说明:
  脚本会基于 chart 的 values.yaml 生成一个临时 values 文件，替换其中的占位符：
    HOSTNAME, API-NAME, TAG, ENVIRONMENT
  如果设置了 HELM_CONTAINER_PORT，还会替换端口占位符：
    API-PORT, API_PORT, CONTAINER-PORT, CONTAINER_PORT, SERVER-PORT, SERVER_PORT
  然后用 helm upgrade --install 进行部署，部署完成后删除临时文件。

可选环境变量:
  KUBECONFIG       默认 /etc/rancher/k3s/k3s.yaml
  HELM_NAMESPACE  未传 namespace 参数时使用，默认 default
  HELM_CONTAINER_PORT  容器 EXPOSE 端口，用于替换 values.yaml 中的端口占位符
  HELM_TIMEOUT    默认 180s
EOF
}

fail() {
  echo "错误: $1" >&2
  usage >&2
  exit "${2:-2}"
}

# Escape replacement text for sed (protect backslashes and &)
escape_for_sed() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g'
}

validate_dns_label() {
  value="$1"
  label="$2"
  if ! printf '%s' "$value" | grep -Eq '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'; then
    fail "${label} 必须是合法的 Kubernetes DNS label: ${value}" 2
  fi
}

validate_port() {
  value="$1"
  label="$2"
  if ! printf '%s' "$value" | grep -Eq '^[0-9]{1,5}$' || [ "$value" -lt 1 ] || [ "$value" -gt 65535 ]; then
    fail "${label} 必须是合法端口: ${value}" 2
  fi
}

# deploy_helm <chart_path> <hostname> <api_name> <tag> <environment> [namespace]
deploy_helm() {
  if [ "$#" -lt 5 ] || [ "$#" -gt 6 ]; then
    fail "参数数量错误。" 2
  fi

  chart_path="$1"
  hostname="$2"
  api_name="$3"
  tag="$4"
  environment="$5"
  namespace="${6:-${HELM_NAMESPACE:-default}}"
  container_port="${HELM_CONTAINER_PORT:-}"

  [ -n "$chart_path" ] || fail "chart_path 不能为空" 2
  [ -n "$hostname" ] || fail "hostname 不能为空" 2
  [ -n "$api_name" ] || fail "api_name 不能为空" 2
  [ -n "$tag" ] || fail "tag 不能为空" 2
  [ -n "$environment" ] || fail "environment 不能为空" 2
  [ -n "$namespace" ] || fail "namespace 不能为空" 2

  validate_dns_label "$api_name" "api_name"
  validate_dns_label "$namespace" "namespace"
  if [ -n "$container_port" ]; then
    validate_port "$container_port" "HELM_CONTAINER_PORT"
  fi

  check_helm
  check_kubectl

  if [ ! -d "${chart_path}" ]; then
    fail "找不到 Helm chart 目录: ${chart_path}" 3
  fi

  if [ ! -f "${chart_path}/values.yaml" ]; then
    fail "找不到 ${chart_path}/values.yaml" 3
  fi

  tmpfile=$(mktemp /tmp/values.XXXXXX.yaml) || { echo "mktemp 失败" >&2; exit 4; }
  tmpfile_port=""
  trap 'rm -f "${tmpfile}" "${tmpfile_port}"' EXIT HUP INT TERM

  h_escaped=$(escape_for_sed "${hostname}")
  ap_escaped=$(escape_for_sed "${api_name}")
  tag_escaped=$(escape_for_sed "${tag}")
  env_escaped=$(escape_for_sed "${environment}")

  sed -e "s|HOSTNAME|${h_escaped}|g" \
      -e "s|API-NAME|${ap_escaped}|g" \
      -e "s|TAG|${tag_escaped}|g" \
      -e "s|ENVIRONMENT|${env_escaped}|g" \
      "${chart_path}/values.yaml" > "${tmpfile}"

  if [ -n "$container_port" ]; then
    port_escaped=$(escape_for_sed "${container_port}")
    tmpfile_port=$(mktemp /tmp/values-port.XXXXXX.yaml) || { echo "mktemp 失败" >&2; exit 4; }
    sed -e "s|API-PORT|${port_escaped}|g" \
        -e "s|API_PORT|${port_escaped}|g" \
        -e "s|CONTAINER-PORT|${port_escaped}|g" \
        -e "s|CONTAINER_PORT|${port_escaped}|g" \
        -e "s|SERVER-PORT|${port_escaped}|g" \
        -e "s|SERVER_PORT|${port_escaped}|g" \
        "${tmpfile}" > "${tmpfile_port}"
    mv "${tmpfile_port}" "${tmpfile}"
    tmpfile_port=""
  fi

  echo "正在部署 Helm release: release=${api_name} chart=${chart_path} namespace=${namespace} containerPort=${container_port:-未设置}"
  helm upgrade --install "${api_name}" "${chart_path}" \
    -f "${tmpfile}" \
    --namespace "${namespace}" \
    --atomic \
    --timeout "${HELM_TIMEOUT:-180s}"

  echo "部署完成: release=${api_name} chart=${chart_path} namespace=${namespace}"
}

# 如果脚本直接被执行，则调用 deploy_helm
if [ "$#" -eq 0 ]; then
  usage
  exit 0
fi

: "${KUBECONFIG:=/etc/rancher/k3s/k3s.yaml}"
export KUBECONFIG

deploy_helm "$@"
