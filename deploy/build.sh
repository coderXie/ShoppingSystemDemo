#!/bin/bash
# ==============================================================================
# 本地构建脚本 — 在开发机上执行，生成可部署的产物
# 用法：在项目根目录执行 bash deploy/build.sh
# ==============================================================================

set -e

echo "=========================================="
echo "  AI Shop Agent — 本地构建"
echo "=========================================="

# 检查在项目根目录
if [ ! -f "pom.xml" ]; then
    echo "❌ 请在项目根目录执行此脚本"
    exit 1
fi

# ---- 1. Maven 打包 ----
echo ""
echo ">>> [1/3] Maven 打包后端 JAR..."
if [ -f "mvnw" ]; then
    ./mvnw package -DskipTests -B
else
    mvn package -DskipTests -B
fi
echo "✅ 后端 JAR 构建完成: target/ai-shop-agent-1.0.0.jar"

# ---- 2. 前端构建 ----
echo ""
echo ">>> [2/3] 构建前端 Vue3 应用..."
cd frontend
if [ ! -d "node_modules" ]; then
    npm ci --registry=https://registry.npmmirror.com
fi
npm run build
cd ..
echo "✅ 前端构建完成: frontend/dist/"

# ---- 3. 检查产物 ----
echo ""
echo ">>> [3/3] 检查构建产物..."

if [ -f "target/ai-shop-agent-1.0.0.jar" ]; then
    JAR_SIZE=$(du -h target/ai-shop-agent-1.0.0.jar | cut -f1)
    echo "  ✅ JAR: target/ai-shop-agent-1.0.0.jar ($JAR_SIZE)"
else
    echo "  ❌ JAR 文件不存在"
    exit 1
fi

if [ -d "frontend/dist" ]; then
    DIST_SIZE=$(du -sh frontend/dist | cut -f1)
    echo "  ✅ 前端: frontend/dist/ ($DIST_SIZE)"
else
    echo "  ❌ frontend/dist/ 目录不存在"
    exit 1
fi

echo ""
echo "=========================================="
echo "  ✅ 构建完成！"
echo ""
echo "  下一步：上传到服务器"
echo "  假设服务器 /opt/ai-shop-agent 为部署目录"
echo ""
echo "  # 创建远程目录"
echo "  ssh user@server 'mkdir -p /opt/ai-shop-agent/{target,frontend,deploy}'"
echo ""
echo "  # 上传文件"
echo "  scp target/ai-shop-agent-1.0.0.jar user@server:/opt/ai-shop-agent/target/"
echo "  scp -r frontend/dist user@server:/opt/ai-shop-agent/frontend/"
echo "  scp deploy/docker-compose.prod.yml user@server:/opt/ai-shop-agent/deploy/"
echo "  scp deploy/Dockerfile.backend.prod user@server:/opt/ai-shop-agent/deploy/"
echo "  scp deploy/Dockerfile.frontend.prod user@server:/opt/ai-shop-agent/deploy/"
echo "  scp deploy/nginx.conf user@server:/opt/ai-shop-agent/deploy/"
echo "  scp deploy/my.cnf user@server:/opt/ai-shop-agent/deploy/"
echo "  scp deploy/.env.example user@server:/opt/ai-shop-agent/deploy/"
echo ""
echo "  # 服务器上启动"
echo "  ssh user@server"
echo "  cd /opt/ai-shop-agent"
echo "  cp deploy/.env.example deploy/.env && vim deploy/.env"
echo "  docker compose -f deploy/docker-compose.prod.yml up -d --build"
echo "=========================================="
