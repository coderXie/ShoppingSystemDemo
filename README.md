# AI Shop Agent — 跨境电商 AI 协同调度系统

基于 **LangGraph4j + Spring Boot 3 + Vue 3** 的智能客服系统，支持 AI 自动处理订单/退款/物流查询，并通过 HITL（Human-in-the-Loop）机制实现退款审批的人工介入。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2.5 (WebFlux), Java 17 |
| 数据库 | MySQL 8.0 (生产), H2 (测试) |
| ORM | Spring Data JPA / Hibernate |
| AI 引擎 | LangChain4j 0.31.0 + LangGraph4j 1.8.17 |
| LLM | DeepSeek (OpenAI 兼容 API) |
| 认证 | JWT (jjwt) + BCrypt |
| 前端 | Vue 3.4, Vite 5.2, Element Plus, Pinia |
| 部署 | Docker Compose, Nginx 反向代理 |

## 项目结构

```
├── src/                          # Spring Boot 后端
│   ├── main/java/com/shop/agent/dispatch/
│   │   ├── controller/           # REST API 控制器
│   │   ├── domain/
│   │   │   ├── agent/            # AI Agent 核心（图构建、状态、服务）
│   │   │   ├── auth/             # 用户认证
│   │   │   ├── inventory/        # 库存管理
│   │   │   ├── logistics/        # 物流管理
│   │   │   └── order/            # 订单管理
│   │   ├── dto/                  # 数据传输对象
│   │   └── infrastructure/       # 安全、JWT
│   └── main/resources/
│       ├── application.properties
│       ├── schema.sql
│       └── data.sql
├── frontend/                     # Vue 3 前端
│   └── src/
│       ├── api/                  # API 调用（SSE 流式通信）
│       ├── views/                # 页面（聊天、登录、管理）
│       └── components/           # 组件（沙箱、审批面板）
├── deploy/                       # 部署配置
│   ├── docker-compose.yml        # Docker 编排
│   ├── Dockerfile.backend        # 后端镜像
│   ├── Dockerfile.frontend       # 前端镜像
│   ├── nginx.conf                # Nginx 配置
│   ├── application-prod.yml      # 生产环境配置
│   └── .env.example              # 环境变量模板
└── pom.xml                       # Maven 配置
```

## 本地开发

### 后端

```bash
# 确保本地 MySQL 运行，数据库名 shop_agent_dispatch
# 设置环境变量
export DEEPSEEK_API_KEY=sk-xxxx

# 启动
./mvnw spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

## 生产部署

```bash
# 1. 克隆项目
git clone <repo-url> /opt/ai-shop-agent
cd /opt/ai-shop-agent

# 2. 配置环境变量
cp deploy/.env.example deploy/.env
vim deploy/.env   # 填写 MySQL 密码、DeepSeek API Key

# 3. 一键启动
docker compose -f deploy/docker-compose.yml up -d --build

# 4. 访问
# 前端: http://服务器IP:6125
```

### 常用命令

```bash
# 查看状态
docker compose -f deploy/docker-compose.yml ps

# 查看后端日志
docker compose -f deploy/docker-compose.yml logs -f ai-backend

# 停止服务
docker compose -f deploy/docker-compose.yml down

# 重新构建并启动
docker compose -f deploy/docker-compose.yml up -d --build
```

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | ✅ | MySQL root 密码 |
| `DB_PASSWORD` | ✅ | 应用数据库密码 |
| `DEEPSEEK_API_KEY` | ✅ | DeepSeek API 密钥 |
| `DB_HOST` | ❌ | 数据库主机（默认 `mysql-db`） |
| `DB_USERNAME` | ❌ | 数据库用户（默认 `ai_agent`） |
| `LLM_BASE_URL` | ❌ | LLM 端点（默认 `https://api.deepseek.com`） |
| `LLM_MODEL_NAME` | ❌ | 模型名（默认 `deepseek-chat`） |
