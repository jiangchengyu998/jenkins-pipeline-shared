#!/bin/sh
set -eu

# deploy_helm.sh - 部署 Helm Chart 的脚本（POSIX-sh 兼容）
# 用法（位置参数）：
#   deploy_helm.sh <chart_path> <hostname> <api_name> <tag> <environment>
# 例如：
#   ./resources/deploy_helm.sh ../charts/springboot-api api.example.com demo-api 1.0.0 prod
ls -al
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
用法: $0 <chart_path> <hostname> <api_name> <tag> <environment>

示例:
  $0 ../charts/springboot-api api.example.com demo-api 1.0.0 prod

说明:
  脚本会基于 chart 的 values.yaml 生成一个临时 values 文件，替换其中的占位符：
    HOSTNAME, API-NAME, TAG, ENVIRONMENT
  然后用 helm upgrade --install 进行部署，部署完成后删除临时文件。
EOF
}

# Escape replacement text for sed (protect backslashes and &)
escape_for_sed() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g'
}

# deploy_helm <chart_path> <hostname> <api_name> <tag> <environment>
deploy_helm() {
  if [ "$#" -lt 5 ]; then
    echo "参数不足。" >&2
    usage
    exit 2
  fi

  chart_path="$1"
  hostname="$2"
  api_name="$3"
  tag="$4"
  environment="$5"

  check_helm
  check_kubectl

  if [ ! -f "${chart_path}/values.yaml" ]; then
    echo "找不到 ${chart_path}/values.yaml" >&2
    exit 3
  fi

  tmpfile=$(mktemp /tmp/values.XXXXXX.yaml) || { echo "mktemp 失败" >&2; exit 4; }
  trap 'rm -f "${tmpfile}"' EXIT

  h_escaped=$(escape_for_sed "${hostname}")
  ap_escaped=$(escape_for_sed "${api_name}")
  tag_escaped=$(escape_for_sed "${tag}")
  env_escaped=$(escape_for_sed "${environment}")

  sed -e "s|HOSTNAME|${h_escaped}|g" \
      -e "s|API-NAME|${ap_escaped}|g" \
      -e "s|TAG|${tag_escaped}|g" \
      -e "s|ENVIRONMENT|${env_escaped}|g" \
      "${chart_path}/values.yaml" > "${tmpfile}"

  echo "正在用临时 values 文件部署: ${tmpfile}"
  echo "helm upgrade --install ${api_name} ${chart_path} -f ${tmpfile}"
  helm upgrade --install "${api_name}" "${chart_path}" \
    -f "${tmpfile}" \
    --atomic \
    --wait \
    --timeout 180s

  echo "部署完成: release=${api_name} chart=${chart_path}"
}

# 如果脚本直接被执行，则调用 deploy_helm
if [ "$#" -eq 0 ]; then
  usage
  exit 0
fi
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

deploy_helm "$@"
