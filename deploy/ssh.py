#!/usr/bin/env python3
"""远程执行器：通过 paramiko 在目标服务器上执行命令。

用法：
    python ssh.py 'uname -a'
    python ssh.py < script.sh          # 从 stdin 读脚本内容

避免在 shell 命令里出现明文密码，凭据统一在本文件 / 环境变量中维护。
"""
import os
import sys
import time

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "workbench.example.com")
USER = os.environ.get("DEPLOY_USER", "root")
# 凭据只从环境变量/本地 .env 取：**仓库里不留明文兜底值**。
# 旧版本把 root 口令写死成默认值，等于把服务器钥匙挂在 GitHub 上。
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

def require_password():
    """缺凭据就立刻停：拿空密码去撞一次 ssh 又慢又像在爆破。"""
    if not PASSWORD:
        sys.exit(
            "缺少 DEPLOY_PASSWORD。\n"
            "  export DEPLOY_PASSWORD=...                        # 仅当前 shell\n"
            "  或 cp deploy/.env.example deploy/.env 后填写        # .env 不入库"
        )

PORT = int(os.environ.get("DEPLOY_PORT", "22"))


def run(command: str, timeout: int = 600):
    require_password()
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=20)
        stdin, stdout, stderr = client.exec_command(command, timeout=timeout, get_pty=False)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        code = stdout.channel.recv_exit_status()
        return code, out, err
    finally:
        client.close()


def main():
    require_password()
    if len(sys.argv) > 1:
        command = sys.argv[1]
    else:
        command = sys.stdin.read()
    transport = None
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=20)
        transport = client.get_transport()
        channel = transport.open_session()
        channel.settimeout(2400)
        channel.exec_command(command)
        out = b""
        err = b""
        while True:
            if channel.recv_ready():
                chunk = channel.recv(4096)
                # 收到空块不代表流结束：recv_ready() 为真时 recv() 仍可能瞬时返回 b""。
                # 早年的写法在这里直接 break，结果是 stdout 被静默截断
                # （E2E 里表现为「接口返回 200 但响应体是空的」这种没法查的假象）。
                # 空块就当作这一轮没读到，交给下面的 exit_status 判断。
                if chunk:
                    out += chunk
                    sys.stdout.write(chunk.decode("utf-8", "replace"))
                    sys.stdout.flush()
                    continue
            if channel.recv_stderr_ready():
                chunk = channel.recv_stderr(4096)
                if chunk:
                    err += chunk
                    sys.stderr.write(chunk.decode("utf-8", "replace"))
                    sys.stderr.flush()
                    continue
            if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
                break
            time.sleep(0.01)
        code = channel.recv_exit_status()
        print(f"\n[exit] {code}")
        sys.exit(code)
    finally:
        client.close()


if __name__ == "__main__":
    main()
