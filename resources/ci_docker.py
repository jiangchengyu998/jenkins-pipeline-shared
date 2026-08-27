"""
    echo "  构建方式: Dockerfile"
    echo "  Dockerfile: ${code_dir}/Dockerfile"
    docker build -t "${project_name}" \
        --build-arg VERSION="${version}" \
        --label "project=${project_name}" \
        --label "build-time=$(date +%Y-%m-%dT%H:%M:%S)" \
        "${code_dir}"
"""
from datetime import datetime
from subprocess import run
import sys

if len(sys.argv) != 4:
    print(f"用法: {sys.argv[0]} <代码目录> <项目名> <镜像版本>", file=sys.stderr)
    sys.exit(2)

code_dir = sys.argv[1]
project_name = sys.argv[2]
version = sys.argv[3]

print(f"  构建方式: Dockerfile\n  Dockerfile: {code_dir}/Dockerfile")

result = run(
    [
        "docker",
        "buildx",
        "build",
        "--load",
        "-t",
        project_name,
        "--build-arg",
        f"VERSION={version}",
        "--label",
        f"project={project_name}",
        "--label",
        f"build-time={datetime.now().isoformat(timespec='seconds')}",
        code_dir,
    ]
)

if result.returncode != 0:
    sys.exit(result.returncode)

print(f"镜像 {project_name} 构建完成，目标版本: {version}")
