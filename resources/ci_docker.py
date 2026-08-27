#!/usr/bin/env python3

"""Use Docker Buildx to build an image for the Jenkins CI pipeline."""

import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional, Sequence


PROJECT_NAME_PATTERN = re.compile(r"^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$")
VERSION_PATTERN = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")


def error(message: str) -> None:
    print(f"错误: {message}", file=sys.stderr, flush=True)


def check_buildx() -> bool:
    if shutil.which("docker") is None:
        error("未找到 docker 命令")
        return False

    try:
        result = subprocess.run(
            ["docker", "buildx", "version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except OSError as exception:
        error(f"无法执行 Docker Buildx: {exception}")
        return False

    if result.returncode != 0:
        detail = result.stderr.strip() or "请确认 Jenkins 镜像已安装 Buildx 插件"
        error(f"Docker Buildx 不可用: {detail}")
        return False

    return True


def build_image(code_dir: Path, project_name: str, version: str) -> int:
    dockerfile = code_dir / "Dockerfile"
    build_time = datetime.now().isoformat(timespec="seconds")
    command = [
        "docker",
        "buildx",
        "build",
        "--load",
        "--progress=plain",
        "--tag",
        project_name,
        "--file",
        str(dockerfile),
        "--build-arg",
        f"VERSION={version}",
        "--label",
        f"project={project_name}",
        "--label",
        f"build-time={build_time}",
        str(code_dir),
    ]

    print("  构建方式: Docker Buildx", flush=True)
    print(f"  Dockerfile: {dockerfile}", flush=True)
    print(f"  项目名称: {project_name}", flush=True)
    print(f"  镜像版本: {version}", flush=True)

    try:
        result = subprocess.run(command)
    except OSError as exception:
        error(f"无法启动 Docker 构建: {exception}")
        return 1

    if result.returncode != 0:
        error(f"Docker 镜像构建失败，退出码: {result.returncode}")
        return result.returncode

    print(f"镜像 {project_name} 构建完成，目标版本: {version}", flush=True)
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    program = Path(sys.argv[0]).name

    if len(args) != 3:
        print(f"用法: {program} <代码目录> <项目名> <镜像版本>", file=sys.stderr)
        return 2

    code_dir = Path(args[0])
    project_name = args[1]
    version = args[2]

    if not code_dir.is_dir():
        error(f"代码目录不存在: {code_dir}")
        return 2
    if not (code_dir / "Dockerfile").is_file():
        error(f"Dockerfile 不存在: {code_dir / 'Dockerfile'}")
        return 2
    if not PROJECT_NAME_PATTERN.fullmatch(project_name):
        error(f"项目名格式无效: {project_name}")
        return 2
    if not VERSION_PATTERN.fullmatch(version):
        error(f"镜像版本格式无效: {version}")
        return 2
    if not check_buildx():
        return 1

    return build_image(code_dir, project_name, version)


if __name__ == "__main__":
    sys.exit(main())
