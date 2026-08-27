#!/usr/bin/env python3

"""Build and push a Docker image from a source directory.

This is the Python equivalent of ``deploy.sh``.  It intentionally keeps the
same positional arguments and environment-variable based configuration so it
can be used as a drop-in replacement in existing Jenkins jobs.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, Mapping, Optional, Sequence


DEFAULT_REGISTRY = "192.168.50.18:5000"
DEFAULT_BUILDER = "paketobuildpacks/builder-jammy-full"


def print_usage(program: str) -> None:
    print(
        f"用法: {program} <代码目录> [环境变量JSON] [项目名] "
        "[仓库用户名] [仓库密码] [镜像版本]"
    )
    print(
        f"示例: {program} /path/to/code '{{\"DB_HOST\":\"localhost\"}}' "
        "my-project '' '' 1.0.0"
    )


def sanitize_project_name(value: str) -> str:
    """Keep the characters accepted by the original shell script."""
    return re.sub(r"[^A-Za-z0-9_-]", "", value).lower()


def sanitize_version(value: str) -> str:
    """Return a Docker-tag-compatible version with the original 128-char cap."""
    return re.sub(r"[^A-Za-z0-9_.-]", "", value)[:128] or "latest"


def run(
    command: Sequence[str],
    *,
    input_text: Optional[str] = None,
    quiet: bool = False,
) -> None:
    kwargs: Dict[str, object] = {"check": True, "text": True}
    if input_text is not None:
        kwargs["input"] = input_text
    if quiet:
        kwargs["stdout"] = subprocess.DEVNULL
        kwargs["stderr"] = subprocess.DEVNULL
    subprocess.run(list(command), **kwargs)


def env_value(environment: Mapping[str, str], primary: str, fallback: str) -> str:
    return environment.get(primary) or environment.get(fallback, "")


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    program = Path(sys.argv[0]).name

    if not args:
        print_usage(program)
        return 1

    code_dir = Path(args[0])
    # Kept for command-line compatibility with deploy.sh. The shell version
    # accepts this value but does not apply it during image construction.
    _envs = args[1] if len(args) > 1 else ""
    project_name_arg = args[2] if len(args) > 2 else ""
    harbor_username = args[3] if len(args) > 3 else ""
    harbor_password = args[4] if len(args) > 4 else ""
    version = sanitize_version(args[5] if len(args) > 5 else "latest")

    if not code_dir.is_dir():
        print(f"错误: 代码目录不存在: {code_dir}")
        return 1

    project_name = sanitize_project_name(project_name_arg or code_dir.name)
    if not project_name:
        print("错误: 项目名为空")
        return 1

    environment = os.environ
    registry = environment.get("DOCKER_REGISTRY") or DEFAULT_REGISTRY
    builder = environment.get("BUILDPACK_BUILDER") or DEFAULT_BUILDER
    buildpack_versions = {
        "BP_JVM_VERSION": env_value(
            environment, "BUILDPACK_JVM_VERSION", "BP_JVM_VERSION"
        ),
        "BP_NODE_VERSION": env_value(
            environment, "BUILDPACK_NODE_VERSION", "BP_NODE_VERSION"
        ),
        "BP_PYTHON_VERSION": env_value(
            environment, "BUILDPACK_PYTHON_VERSION", "BP_PYTHON_VERSION"
        ),
        "BP_GO_VERSION": env_value(
            environment, "BUILDPACK_GO_VERSION", "BP_GO_VERSION"
        ),
    }

    log_dir = Path("/var/log") / project_name
    log_dir.mkdir(parents=True, exist_ok=True)

    print(f"项目名称: {project_name}")
    print(f"代码目录: {code_dir}")
    print(f"日志目录: {log_dir}")
    print(f"镜像版本: {version}")
    print(f"镜像仓库: {registry}")

    dockerfile = code_dir / "Dockerfile"
    use_buildpacks = not dockerfile.is_file()
    if use_buildpacks:
        print("未找到Dockerfile，使用 Cloud Native Buildpacks 构建镜像")
    else:
        print("使用项目中的Dockerfile")

    try:
        run(["docker", "info"], quiet=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        print("错误: Docker守护进程未运行")
        return 1

    print("开始构建Docker镜像...")
    if use_buildpacks:
        if shutil.which("pack") is None:
            print(
                "错误: 未找到 pack 命令。无Dockerfile并使用 buildpacks 构建时"
                "需要安装 Cloud Native Buildpacks pack CLI"
            )
            return 1

        pack_command = [
            "pack",
            "build",
            project_name,
            "--path",
            str(code_dir),
            "--builder",
            builder,
        ]
        version_labels = {
            "BP_JVM_VERSION": "JVM",
            "BP_NODE_VERSION": "Node.js",
            "BP_PYTHON_VERSION": "Python",
            "BP_GO_VERSION": "Go",
        }
        for variable, value in buildpack_versions.items():
            if value:
                pack_command.extend(["--env", f"{variable}={value}"])
                print(f"  {version_labels[variable]}版本: {value}")

        print("  构建方式: buildpacks")
        print(f"  Builder: {builder}")
        run(pack_command)
    else:
        print("  构建方式: Dockerfile")
        print(f"  Dockerfile: {dockerfile}")
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
            ]
        )

    print(f"镜像构建完成: {project_name}")

    if environment.get("DOCKER_REGISTRY_LOGIN", "false") == "true":
        if not harbor_username or not harbor_password:
            print("错误: DOCKER_REGISTRY_LOGIN=true 时必须提供仓库用户名和密码")
            return 1
        print(f"登录Docker仓库: {registry}")
        run(
            ["docker", "login", registry, "-u", harbor_username, "--password-stdin"],
            input_text=harbor_password,
        )

    image_tag = f"{registry}/{project_name}:{version}"
    run(["docker", "tag", project_name, image_tag])
    print(f"镜像打标签完成: {image_tag}")
    run(["docker", "push", image_tag])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
    except OSError as error:
        print(f"错误: {error}", file=sys.stderr)
        raise SystemExit(1) from None
