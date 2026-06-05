-- ============================================================
-- 智能客服+供应链异常协同调度系统 - 数据库初始化脚本
-- 适配 MySQL 8.x，字符集 utf8mb4
-- ============================================================

-- agent_checkpoints 由 CheckpointTableFix 在启动时自动创建，此处无需处理

-- ------------------------------------------------------------
-- 1. 订单表 orders
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id         VARCHAR(64)     NOT NULL COMMENT '用户ID',
    total_amount    DECIMAL(12,2)   NOT NULL COMMENT '订单总金额',
    status          VARCHAR(32)     NOT NULL COMMENT '订单状态: PENDING_PAY, SHIPPED, REFUND_PENDING, REFUNDED',
    create_time     DATETIME        NOT NULL COMMENT '下单时间',

    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单实体表';

-- ------------------------------------------------------------
-- 2. 商品/库存表 products
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name            VARCHAR(256)    NOT NULL COMMENT '商品名称',
    price           DECIMAL(12,2)   NOT NULL COMMENT '商品单价',
    stock_count     INT             NOT NULL DEFAULT 0 COMMENT '当前海外仓库存数量',

    INDEX idx_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '商品/库存实体表';

-- ------------------------------------------------------------
-- 3. 订单明细表 order_items
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_items (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id        BIGINT UNSIGNED NOT NULL COMMENT '对应订单ID',
    product_id      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity        INT             NOT NULL COMMENT '购买数量',
    unit_price      DECIMAL(12,2)   NOT NULL COMMENT '下单时单价',

    INDEX idx_order_id (order_id),
    INDEX idx_product_id (product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单明细项表';

-- ------------------------------------------------------------
-- 4. 物流轨迹表 logistics
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS logistics (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id        BIGINT UNSIGNED NOT NULL COMMENT '对应订单ID',
    tracking_number VARCHAR(128)    NOT NULL COMMENT '跨境物流单号',
    status          VARCHAR(32)     NOT NULL COMMENT '物流状态: IN_TRANSIT, DELIVERED',
    last_location   VARCHAR(512)    NULL COMMENT '最新物流位置快照',

    INDEX idx_order_id (order_id),
    INDEX idx_tracking_number (tracking_number),
    INDEX idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '物流轨迹实体表';

-- ------------------------------------------------------------
-- 4. AI 退款审批日志表 approval_logs
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS approval_logs (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id        BIGINT UNSIGNED NOT NULL COMMENT '对应订单ID',
    agent_reason    TEXT            NOT NULL COMMENT 'AI Agent 提交的退款理由/供应链异常分析',
    status          VARCHAR(32)     NOT NULL COMMENT '审批状态: PENDING, APPROVED, REJECTED',
    manager_comment TEXT            NULL COMMENT '人工管理员的审核批注',

    INDEX idx_order_id (order_id),
    INDEX idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AI退款审批日志实体表';

-- ------------------------------------------------------------
-- 5. 系统用户表 sys_user
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username        VARCHAR(64)     NOT NULL UNIQUE COMMENT '用户名',
    password        VARCHAR(128)    NOT NULL COMMENT 'BCrypt 加密后的密码',
    role            VARCHAR(32)     NOT NULL COMMENT '角色: MANAGER, USER',

    INDEX idx_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '系统用户表';
