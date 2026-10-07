#!/usr/bin/env python3
"""SFTP 上传器：把本地文件或目录推送到目标服务器。

用法：
    python upload.py <local_path> <remote_path>
"""
import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "workbench.example.com")
PORT = int(os.environ.get("DEPLOY_PORT", "22"))
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



def ensure_dir(sftp, remote_dir):
    parts = [p for p in remote_dir.replace("\\", "/").split("/") if p]
    current = ""
    for part in parts:
        current += "/" + part
        try:
            sftp.stat(current)
        except OSError:
            sftp.mkdir(current)
            print(f"[mkdir] {current}")


def upload(sftp, local, remote, ensure_remote_dir=False):
    if ensure_remote_dir:
        ensure_dir(sftp, os.path.dirname(remote))
    sftp.put(local, remote)
    print(f"[put] {local} -> {remote}")


def upload_tree(sftp, local_root, remote_root):
    for root, _dirs, files in os.walk(local_root):
        relative = os.path.relpath(root, local_root)
        remote_dir = remote_root if relative == "." else f"{remote_root}/{relative}"
        ensure_dir(sftp, remote_dir)
        for name in files:
            local_file = os.path.join(root, name)
            remote_file = f"{remote_dir}/{name}"
            sftp.put(local_file, remote_file)
            print(f"[put] {local_file} -> {remote_file}")


def main():
    require_password()
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    local_path, remote_path = sys.argv[1], sys.argv[2]
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=20)
        sftp = client.open_sftp()
        if os.path.isdir(local_path):
            upload_tree(sftp, os.path.abspath(local_path), remote_path)
        else:
            upload(sftp, local_path, remote_path, ensure_remote_dir=True)
        print("[done]")
    finally:
        sftp.close()
        client.close()


if __name__ == "__main__":
    main()
