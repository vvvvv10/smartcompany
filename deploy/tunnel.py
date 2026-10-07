#!/usr/bin/env python3
"""本地端口转发：把远端只有内网能访问的服务映射到本机。

用法：
    python tunnel.py [local_port] [remote_port]

例：python tunnel.py 18080 8080   ->  本机 http://127.0.0.1:18080 打开远端 8080
"""
import os
import select
import socket
import sys
import threading

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

REMOTE_HOST = "127.0.0.1"

LOCAL_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
REMOTE_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080


def pipe(sock, chan):
    while True:
        readable, _, _ = select.select([sock, chan], [], [], 5)
        if sock in readable:
            data = sock.recv(4096)
            if not data:
                break
            chan.send(data)
        if chan in readable:
            data = chan.recv(4096)
            if not data:
                break
            sock.send(data)
    chan.close()
    sock.close()


def main():
    require_password()
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=20)
    transport = client.get_transport()

    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", LOCAL_PORT))
    listener.listen(10)
    print(f"tunnel ready: http://127.0.0.1:{LOCAL_PORT} -> {HOST}:{REMOTE_PORT}", flush=True)

    while True:
        conn, addr = listener.accept()
        chan = transport.open_channel("direct-tcpip", (REMOTE_HOST, REMOTE_PORT), addr)
        threading.Thread(target=pipe, args=(conn, chan), daemon=True).start()


if __name__ == "__main__":
    main()
