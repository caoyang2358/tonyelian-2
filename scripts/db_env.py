#!/usr/bin/env python3
"""scripts/ 下验收脚本共用的连库配置与前置探活。

只服务于本地验收脚本，不参与应用运行。端口解析优先级与历史保持一致：

    环境变量 DB_PORT > 项目根 .env > 内置默认值 3306

改动只落在失败路径上：

  · 连不上时立刻退出，并说明「这个端口是从哪来的」，而不是等到脚本末尾清理阶段
    才抛 Access denied——那时测试数据已经造好，会留下孤儿测点、孤儿参数；
  · 3306 常被本机其他 MySQL 实例占用（账号也不同），提前失败也避免把校验数据
    写进别人的库。

用法：

    from db_env import load_config, preflight

    CONFIG = load_config()
    preflight(CONFIG)      # 放在任何造数据动作之前
"""

from __future__ import annotations

import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_PORT = "3306"  # 与历史行为一致：三层都缺省时回落到 MySQL 出厂端口
DEFAULT_MYSQL_BIN = "D:/MySQL/MySQL Server 8.0/bin/mysql.exe"

PORT_SOURCE_ENV = "环境变量 DB_PORT"
PORT_SOURCE_FILE = ".env 文件"
PORT_SOURCE_DEFAULT = "脚本内置默认值（未配置）"


def read_env_file() -> dict[str, str]:
    """读项目根目录的 .env；不存在时返回空字典。"""
    values: dict[str, str] = {}
    env_file = ROOT / ".env"
    if not env_file.exists():
        return values
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def resolve_mysql_bin(file_env: dict[str, str] | None = None) -> str:
    """定位 mysql 客户端：环境变量 > .env > 常见安装路径 > PATH 里的 mysql。"""
    file_env = file_env if file_env is not None else read_env_file()
    override = os.environ.get("MYSQL_BIN") or file_env.get("MYSQL_BIN")
    if override and pathlib.Path(override).exists():
        return override
    for candidate in (DEFAULT_MYSQL_BIN, "/usr/bin/mysql", "/usr/local/bin/mysql"):
        if pathlib.Path(candidate).exists():
            return candidate
    return "mysql"


def load_config() -> dict[str, str]:
    """返回 host/port/port_source/name/user/password/bin，优先级与历史一致。"""
    file_env = read_env_file()
    if os.environ.get("DB_PORT"):
        port, port_source = os.environ["DB_PORT"], PORT_SOURCE_ENV
    elif file_env.get("DB_PORT"):
        port, port_source = file_env["DB_PORT"], PORT_SOURCE_FILE
    else:
        port, port_source = DEFAULT_PORT, PORT_SOURCE_DEFAULT
    return {
        "host": os.environ.get("DB_HOST") or file_env.get("DB_HOST") or "127.0.0.1",
        "port": port,
        "port_source": port_source,
        "name": os.environ.get("DB_NAME") or file_env.get("DB_NAME", ""),
        "user": os.environ.get("DB_USER") or file_env.get("DB_USER", ""),
        "password": os.environ.get("DB_PASSWORD") or file_env.get("DB_PASSWORD", ""),
        "bin": resolve_mysql_bin(file_env),
    }


def mysql_command(config: dict[str, str], statement: str, tuple_only: bool = False) -> list[str]:
    """拼 mysql 命令行；沿用脚本原有口径：-N 只输出值、不带列名。"""
    # 密码为空时也必须用 --password=：单独一个 -p 会让 mysql 转去交互式索要密码而卡住。
    password = "--password=" + (config.get("password") or "")
    command = [
        config["bin"], "--no-defaults", "--default-character-set=utf8mb4",
        "-h" + config["host"], "-P" + str(config["port"]),
        "-u" + (config["user"] or "root"), password,
        "-N",
    ]
    if tuple_only:
        command.append("-B")
    command += [config["name"], "-e", statement]
    return command


def _reason_line(stderr: str) -> str:
    lines = [line for line in (stderr or "").strip().splitlines()
             if line.strip() and not line.startswith("mysql: [Warning]")]
    return lines[-1].strip() if lines else "（mysql 未输出原因）"


def _diagnose(config: dict[str, str], stderr: str) -> str:
    reason = _reason_line(stderr)
    lines = [
        "数据库连接失败：%s:%s（端口来源：%s）"
        % (config["host"], config["port"], config["port_source"]),
        "  账号 %s / 库 %s" % (config["user"] or "(未配置)", config["name"] or "(未配置)"),
        "  原因：%s" % reason,
    ]
    if "Access denied" in reason:
        lines.append("  判断：账号或密码和这个实例不匹配，多半是连错了端口（不同实例账号不同）。")
    elif "Unknown database" in reason:
        lines.append("  判断：这个实例上没有 %s 库，多半是连错了端口。" % config["name"])
    elif "Can't connect" in reason or "10061" in reason:
        lines.append("  判断：该端口没有 MySQL 在监听。")
    lines += [
        "  处理：① 核对仓库根 .env 的 DB_PORT 是否就是运行中实例的端口；",
        "        ② 或临时指定：export DB_PORT=<实际端口>（PowerShell: $env:DB_PORT=\"<端口>\"）；",
        "        ③ 查看本机监听：netstat -ano | findstr LISTENING",
        "  脚本尚未创建测试数据，修正后可直接重跑。",
    ]
    return "\n".join(lines)


def preflight(config: dict[str, str], require_mysql: bool = True) -> bool:
    """动数据之前先探一次库。

    返回 True 表示可连库。找不到 mysql 客户端时：require_mysql=True 直接退出，
    否则打印提示并返回 False（保留调用方原有的「跳过连库检查」逻辑）。
    客户端存在但连不上则一律退出，避免半路崩溃留下孤儿数据。
    """
    binary = config.get("bin") or ""
    if not config.get("name") or not config.get("user"):
        raise SystemExit("数据库连接信息不完整：请检查仓库根 .env 的 DB_NAME / DB_USER / DB_PASSWORD。")
    if binary != "mysql" and not pathlib.Path(binary).exists():
        print("[DB] 未找到 MySQL 客户端（%s）。" % binary)
        if require_mysql:
            raise SystemExit("请在 .env 或环境变量 MYSQL_BIN 中指定 mysql 客户端路径。")
        return False

    try:
        result = subprocess.run(mysql_command(config, "SELECT 1;"),
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
    except FileNotFoundError:
        print("[DB] 未找到 MySQL 客户端（%s）。" % binary)
        if require_mysql:
            raise SystemExit("请在 .env 或环境变量 MYSQL_BIN 中指定 mysql 客户端路径。")
        return False
    if result.returncode != 0:
        raise SystemExit(_diagnose(config, result.stderr))
    print("[DB] %s:%s 连接正常（端口来源：%s）"
          % (config["host"], config["port"], config["port_source"]))
    return True
