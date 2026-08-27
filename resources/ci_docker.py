"""
    echo "  构建方式: Dockerfile"
    echo "  Dockerfile: ${code_dir}/Dockerfile"
    docker build -t "${project_name}" \
        --build-arg VERSION="${version}" \
        --label "project=${project_name}" \
        --label "build-time=$(date +%Y-%m-%dT%H:%M:%S)" \
        "${code_dir}"
"""
from subprocess import run
from datetime import datetime

print(f"  构建方式: Dockerfile\n  Dockerfile: {code_dir}/Dockerfile")

run(
    [
        "docker",
        "build",
        "-t",
        project_name,
        "--build-arg",
        f"VERSION={version}",
        "--label",
        f"project={project_name}",
        "--label",
        f"build-time={datetime.now().isoformat(timespec='seconds')}",
        str(code_dir),
    ])