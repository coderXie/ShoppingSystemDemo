#!/bin/bash
# ==============================================================================
# 服务器部署脚本 — 在服务器上执行
# 前提：已通过 scp 上传构建产物
# 用法：cd /opt/ai-shop-agent && bash deploy/deploy.sh
# ==============================================================================

set -e

echo "=========================================="
echo "  AI Shop Agent — 服务器部署"
echo "=========================================="

# ---- 1. 检查 Docker ----
echo ""
echo ">>> [1/5] 检查 Docker 环境..."
if ! command -v docker &> /dev/null; then
    echo "❌ Docker 未安装，正在安装..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
    echo "✅ Docker 安装完成"
else
    echo "  ✅ Docker: $(docker --version)"
fi

if ! docker compose version &> /dev/null; then
    echo "❌ Docker Compose 未安装"
    exit 1
else
    echo "  ✅ Docker Compose: $(docker compose version --short)"
fi

# ---- 2. 检查 .env ----
echo ""
echo ">>> [2/5] 检查环境变量配置..."
ENV_FILE="deploy/.env"
if [ ! -f "$ENV_FILE" ]; then
    if [ -f "deploy/.env.example" ]; then
        cp deploy/.env.example "$ENV_FILE"
        echo "  ⚠️  已从 .env.example 创建 .env，请编辑填写真实密码和 API Key："
        echo "     vim $ENV_FILE"
        echo ""
        echo "  填写完成后重新运行此脚本。"
        exit 0
    else
        echo "  ❌ .env.example 也不存在，请手动创建 $ENV_FILE"
        exit 1
    fi
else
    echo "  ✅ $ENV_FILE 已存在"
fi

# ---- 3. 检查构建产物 ----
echo ""
echo ">>> [3/5] 检查构建产物..."
MISSING=0
if [ ! -f "target/ai-shop-agent-1.0.0.jar" ]; then
    echo "  ❌ target/ai-shop-agent-1.0.0.jar 不存在"
    MISSING=1
fi
if [ ! -d "frontend/dist" ]; then
    echo "  ❌ frontend/dist/ 不存在"
    MISSING=1
fi
if [ $MISSING -eq 1 ]; then
    echo "  请先在本地执行 bash deploy/build.sh 构建，然后 scp 上传产物。"
    exit 1
fi
echo "  ✅ 构建产物就绪"

# ---- 4. 构建并启动 ----
echo ""
echo ">>> [4/5] 构建 Docker 镜像并启动服务..."
docker compose -f deploy/docker-compose.prod.yml up -d --build

# ---- 5. 等待并验证 ----
echo ""
echo ">>> [5/5] 等待服务启动..."
sleep 10

echo ""
echo "=========================================="
echo "  服务状态"
echo "=========================================="
docker compose -f deploy/docker-compose.prod.yml ps

echo ""
echo "=========================================="
echo "  ✅ 部署完成！"
echo ""
echo "  访问地址: http://$(hostname -I | awk '{print $1}'):6125"
echo ""
echo "  常用命令："
echo "    查看日志: docker compose -f deploy/docker-compose.prod.yml logs -f"
echo "    重启服务: docker compose -f deploy/docker-compose.prod.yml restart"
echo "    停止服务: docker compose -f deploy/docker-compose.prod.yml down"
echo "    查看后端日志: docker logs -f ai-backend"
echo "    查看 MySQL 日志: docker logs -f ai-mysql"
echo "=========================================="
