#!/usr/bin/env bash
# =============================================================================
# start-dev.sh — 本地同时启动 Spring Boot 后端 + Vue 前端（Git Bash / WSL / macOS / Linux）
# =============================================================================
#
# 用法（在项目根目录）：
#   chmod +x start-dev.sh          # 仅首次需要
#   ./start-dev.sh                 # 同时启动前后端（后台），日志写到 /tmp
#   ./start-dev.sh --backend-only  # 只启动后端（前台）
#   ./start-dev.sh --frontend-only # 只启动前端（前台）
#
# 地址：
#   后端 API     http://localhost:8081/api
#   健康检查     http://localhost:8081/api/actuator/health
#   前端页面     http://localhost:5173
#
# 前置依赖（脚本不会启动它们）：
#   PostgreSQL  localhost:55432
#   Neo4j       localhost:7687
#   Node.js     >= 20.19
#   JDK         21
#
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="${PROJECT_ROOT}/ai-literature-frontend"
MODE="${1:-both}"

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
  else
    # Git Bash / 无 lsof 时退化为 PowerShell
    powershell.exe -NoProfile -Command "if (Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >/dev/null 2>&1
  fi
}

start_backend() {
  if port_in_use 8081; then
    echo "端口 8081 已被占用，认为后端已在运行：http://localhost:8081/api"
    return 0
  fi
  echo "[$(date '+%H:%M:%S')] 启动后端  Spring Boot  →  http://localhost:8081/api"
  cd "${PROJECT_ROOT}"
  # -DskipTests：本地启动不跑测试。Flyway 校验失败时可加：
  #   SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false
  ./mvnw -DskipTests spring-boot:run
}

start_frontend() {
  if port_in_use 5173; then
    echo "端口 5173 已被占用，认为前端已在运行：http://localhost:5173"
    return 0
  fi
  echo "[$(date '+%H:%M:%S')] 启动前端  Vite        →  http://localhost:5173"
  cd "${FRONTEND_DIR}"
  # 依赖未安装时先装；vite 把 /api 代理到 localhost:8081
  if [ ! -d node_modules ]; then
    echo "未找到 node_modules，正在 npm install..."
    npm install
  fi
  npm run dev
}

echo "项目目录: ${PROJECT_ROOT}"

case "${MODE}" in
  --backend-only)
    start_backend
    ;;
  --frontend-only)
    start_frontend
    ;;
  both|"")
    # 同时启动：后端后台，前端前台（Ctrl+C 只停前端；后端需另杀进程）
    if port_in_use 8081; then
      echo "端口 8081 已被占用，跳过后端启动。"
    else
      echo "[$(date '+%H:%M:%S')] 后台启动后端，日志: ${PROJECT_ROOT}/logs/backend-dev.log"
      mkdir -p "${PROJECT_ROOT}/logs"
      (
        cd "${PROJECT_ROOT}"
        ./mvnw -DskipTests spring-boot:run
      ) > "${PROJECT_ROOT}/logs/backend-dev.log" 2>&1 &
      echo "后端 PID: $!"
    fi
    start_frontend
    ;;
  *)
    echo "未知参数: ${MODE}"
    echo "用法: $0 [--backend-only|--frontend-only]"
    exit 1
    ;;
esac
