#!/usr/bin/env python3

"""Build a local image with Cloud Native Buildpacks."""

import os
import shutil
import subprocess
import sys


DEFAULT_BUILDER = "paketobuildpacks/builder-jammy-full"


def env_value(primary: str, fallback: str) -> str:
    return os.environ.get(primary) or os.environ.get(fallback, "")


def main() -> int:
    if len(sys.argv) != 3:
        print(f"用法: {sys.argv[0]} <代码目录> <项目名>", file=sys.stderr)
        return 2

    code_dir = sys.argv[1]
    project_name = sys.argv[2]

    if shutil.which("pack") is None:
        print(
            "错误: 未找到 pack 命令。无Dockerfile并使用 buildpacks 构建时"
            "需要安装 Cloud Native Buildpacks pack CLI",
            file=sys.stderr,
        )
        return 1

    builder = os.environ.get("BUILDPACK_BUILDER") or DEFAULT_BUILDER
    buildpack_versions = (
        (
            "BP_JVM_VERSION",
            "JVM",
            env_value("BUILDPACK_JVM_VERSION", "BP_JVM_VERSION"),
        ),
        (
            "BP_NODE_VERSION",
            "Node.js",
            env_value("BUILDPACK_NODE_VERSION", "BP_NODE_VERSION"),
        ),
        (
            "BP_PYTHON_VERSION",
            "Python",
            env_value("BUILDPACK_PYTHON_VERSION", "BP_PYTHON_VERSION"),
        ),
        (
            "BP_GO_VERSION",
            "Go",
            env_value("BUILDPACK_GO_VERSION", "BP_GO_VERSION"),
        ),
    )

    pack_command = [
        "pack",
        "build",
        project_name,
        "--path",
        code_dir,
        "--builder",
        builder,
    ]

    for variable, language, version in buildpack_versions:
        if version:
            pack_command.extend(["--env", f"{variable}={version}"])
            print(f"  {language}版本: {version}")

    print("  构建方式: buildpacks")
    print(f"  Builder: {builder}")
    return subprocess.run(pack_command).returncode


if __name__ == "__main__":
    sys.exit(main())
