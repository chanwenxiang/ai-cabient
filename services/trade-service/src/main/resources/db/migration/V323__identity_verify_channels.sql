-- V323: 实名核验通道（A 微信官方 + B 第三方人脸），B8 已定「A+B 组合」
--
-- 背景（2026-10-07 核实）
-- ------------------------------------------------------------------
-- 现状问题（V322 已修的缺陷暴露了根因）：
--   `identity-verify.base-url` 是**占位**（.env.example 里注释掉）⇒ 第三方实名字段从未接入。
--   而 `user.verified` 却是「能否开门/免密支付/提现」的**唯一凭证**。
--
-- 用户拍板（B8）：**A + B 组合**
--   A = 微信官方「实名信息校验」  → 免费，但**只覆盖已在微信支付实名的用户**
--   B = 第三方人脸核身→ 按次收费，覆盖全量
--
-- 🔴 取证结论 1：**老系统（easygo）走的是第四条路（法大大 CA），不能照抄**
--   `EgoOptUserAccountDetail:13-37` 存了完整身份证号 + 三张证件照 + 银行卡号
--   ⇒ 那是「运营商签约 + 保证金」业务的刚需；我们是 C 端买饮料，签约对 C 端无价值。
--   ⇒ 且存完整证件号与 V321 的隐私硬约束相反（不落地= 缩小泄露面）。
--
-- 🔴 取证结论 2：微信官方 A **只能「核对」微信支付已有的实名，不能「获取」**
--   微信官方原话：「实名信息是获取不到的，只能获取昵称和绑定手机号」。
--   ⇒ A 的输入需要**完整证件号**（`cred_id`），不是后 4 位
--   ⇒ 但我们**转发即丢**（不落库），符合隐私约束。
--
-- ⚠️ 取证结论 3：`checkrealnameinfo` 是 **intp 业务接口**，字段语义**尚未取证**
--   （未见官方文档）；本迁移只做**通道骨架**，判据一律保守（不通过就拒）。

-- ------------------------------------------------------------------
-- ① 通道类型（受控枚举的持久化形态）
-- ------------------------------------------------------------------
-- 🔴 只加**字符串列**不加 CHECK：通道会随接入方增加，CHECK 每次加类型都要改约束。
--    合法性由应用侧 `IdentityVerifyChannel` 枚举守住。
ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS verify_channel VARCHAR(24);

-- 迁移不设默认值：NULL = 未经核验（与 verified 布尔位配合）。
-- 🔴 刻意**不回填** 'MOCK'：存量 verified=true 的行是历史 mock 产物，
--    标成 MOCK 会让「有多少真实实名用户」这个问题得到错误答案（应为 0）。
COMMENT ON COLUMN user_info.verify_channel IS
    'V323 核验通道：WECHAT_PAY(微信官方A) / FACE(第三方人脸B) / MOCK(开发环境假通过)。NULL=未经核验。';

-- ------------------------------------------------------------------
-- ② 微信官方 A 需要的「授权预下单」痕迹
-- ------------------------------------------------------------------
-- A 的流程（不可跳过）：
--   后端 POST https://api.mch.weixin.qq.com/appauth/wxauthpreorder  → 得 preorder_id（600s 有效）
--   小程序跳「微信城市服务」授权页（固定 appid wx308bd2aeb83d3345
--     路径 subPages/city/wxpay-auth/main）→ 用户同意 → 回跳带 code
--   后端 GET /appauth/getaccesstoken 换 access_token
--   后端 POST https://api.weixin.qq.com/intp/realname/checkrealnameinfo
--     { openid, real_name, cred_id, code }
-- ⚠️ preorder_id **只有 600 秒有效期且只能重入一次** ⇒ 必须落库，
--    否则小程序来回跳的过程中后端拿不到它。
CREATE TABLE IF NOT EXISTS wechat_realname_preorder (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    open_id         VARCHAR(128) NOT NULL,
    preorder_id     VARCHAR(64)  NOT NULL,
    channel         VARCHAR(24)  NOT NULL DEFAULT 'WECHAT_PAY',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at     TIMESTAMPTZ,
    -- 同一用户同时只允许一个未消费的预下单 ⇒ 重复点击「实名」不会产生多份脏数据
    CONSTRAINT uk_wechat_realname_active
        UNIQUE (user_id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX IF NOT EXISTS idx_wechat_realname_preorder_user
    ON wechat_realname_preorder (user_id, created_at DESC);

COMMENT ON TABLE wechat_realname_preorder IS
    'V323 微信官方实名「预下单」临时态（preorder_id 600s 有效、需跨请求传递）。只存标识，不存证件号。';
COMMENT ON COLUMN wechat_realname_preorder.preorder_id IS
    '微信返回的预下单标识。⚠️ 600秒有效，过期需重新预下单（不要缓存复用）。';
COMMENT ON COLUMN wechat_realname_preorder.consumed_at IS
    '核验完成时间；NULL = 仍在有效期内。超过 600s 未消费的行需清理（见 XXL-JOB 待办）。';

-- 幂等：脚本可能重跑
ALTER TABLE wechat_realname_preorder
    DROP CONSTRAINT IF EXISTS uk_wechat_realname_active;
ALTER TABLE wechat_realname_preorder
    ADD CONSTRAINT uk_wechat_realname_active UNIQUE (user_id);

-- ------------------------------------------------------------------
-- ③ 核验尝试留痕（合规需要「谁在何时用什么方式核验」）
-- ------------------------------------------------------------------
-- 🔴 为什么这次**要**留痕而证件号不留：
--    留痕 = 证明「做了核验」；证件号 = 敏感数据本体。
--    审计只需要前者。
CREATE TABLE IF NOT EXISTS identity_verify_attempt (
    attempt_id      BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    channel         VARCHAR(24)  NOT NULL,
    passed          SMALLINT     NOT NULL DEFAULT 0,
    -- 🔴 只存**不可逆摘要**，不存证件号/姓名：
    --    用途是「同一个人是否重复提交」与排障，不需要能还原出证件号。
    subject_digest  VARCHAR(64),
    fail_reason     VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_identity_verify_attempt_user
    ON identity_verify_attempt (user_id, created_at DESC);

-- 同一用户+通道的尝试只留最近 30 条（防无限增长；DELETE 交给定时任务）
COMMENT ON TABLE identity_verify_attempt IS
    'V323 实名核验尝试留痕（合规：证明做过核验）。🔴 不存姓名/证件号，只存 subject_digest。';

-- 迁移后必须验的
-- 1. SELECT column_name FROM information_schema.columns
--    WHERE table_name='user_info' AND column_name='verify_channel';
-- 2. \d wechat_realname_preorder / \d identity_verify_attempt
-- 3. 负向：uk_wechat_realname_active 重复插同一 user_id 应被拒
