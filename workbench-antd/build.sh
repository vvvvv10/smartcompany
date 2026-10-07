#!/usr/bin/env bash
#
# workbench-antd 一键构建。
#
# 流程固定三步：web 打包（tsc + vite）→ cap sync（把 dist 拷进安卓工程的 assets）
# → gradle 打 APK。中间任何一步挂了都会停下来，不会留下"dist 是旧的、APK 也是旧的"
# 这种看不出对错的产物。
#
# 用法：
#   ./build.sh                     # 跑单测 + 打 debug APK
#   ./build.sh --skip-tests        # 跳过单测
#   ./build.sh :app:assembleRelease:app:assembleDebug   # 透传 gradle 任务（发布双包）
#
# 为什么不用 gradle wrapper：services.gradle.org 在这个网络环境只有 ~100B/s，
# wrapper 下载 8.2.1 会卡死；本地 8.7 是现成的（和 workbench-android 用同一个）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
GRADLE="$HOME/gradle/gradle-8.7/bin/gradle"
SKIP_TESTS=0
TASKS=()

for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    *) TASKS+=("$arg") ;;
  esac
done
[ ${#TASKS[@]} -eq 0 ] && TASKS=(:app:assembleDebug)

cd "$ROOT/web"

if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "==> 单测"
  npm test
fi

echo "==> 构建 web（tsc + vite）"
npm run build

echo "==> 同步到 Android 工程"
npx cap sync android

cd "$ROOT/web/android"
echo "==> gradle ${TASKS[*]}"
"$GRADLE" --no-daemon "${TASKS[@]}"

echo
echo "APK: $ROOT/web/android/app/build/outputs/apk/debug/app-debug.apk"