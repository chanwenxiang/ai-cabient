-- COMPETITOR_REF: CB-022
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: jiangyi_device（全新独立表，0 行起；将邑柜数级~百级）
-- LOCK_RISK: low
-- ROLLBACK: DROP TABLE jiangyi_device（上线初期无数据时可逆）
-- NOTES: V332（将邑开门柜接入①，模式一）：将邑设备目录表。
-- 与我方设备主表解耦（主表不动，注释提及 device_info 仅为说明关系，DDL 不触碰热表）：
-- device_id 复用我方设备主键（VARCHAR(64)）；
-- device_sn = 将邑套件出厂 SN（16 位十六进制，如 2b26552554fb7bf9），token 签发/绑租户/补货查询用；
-- identifier = 将邑签发设备编号（CQYB+序号，如 CQYB11253），WS 路径 /websocket/device/{identifier} 用。
-- status：UNBOUND(未入驻)→BOUND(已绑租户，可签发 token)→RETIRED(退役，token 全拒)；
-- token_version：每次吊销 +1，JWT claims 携带，网关比对实现无状态吊销（表不落 token 本体）；
-- model_name/classes_version：随二期模型同步回填，供 modelVersion 上报与映射预生成。
-- 非并发建索引：CREATE INDEX CONCURRENTLY 在 Flyway 社区版事务块内不可用（对齐 V301/V305 先例）；
-- idx_jiangyi_device_status 建在全新空表上，短锁可接受。
-- 竞品：将邑为端侧识别套件供应商（docs/COMPETITOR_BENCHMARK.md CB-022）。

CREATE TABLE jiangyi_device (
    device_id         VARCHAR(64)  PRIMARY KEY,
    device_sn         VARCHAR(64)  NOT NULL,
    identifier        VARCHAR(32)  NOT NULL,
    model_name        VARCHAR(128),
    classes_version   VARCHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'UNBOUND',
    token_version     BIGINT       NOT NULL DEFAULT 0,
    token_issued_at   TIMESTAMPTZ,
    last_ws_online_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_jiangyi_device_sn         UNIQUE (device_sn),
    CONSTRAINT uk_jiangyi_device_identifier UNIQUE (identifier),
    CONSTRAINT ck_jiangyi_device_status CHECK (status IN ('UNBOUND', 'BOUND', 'RETIRED'))
);

CREATE INDEX idx_jiangyi_device_status ON jiangyi_device (status);

COMMENT ON TABLE jiangyi_device IS '将邑设备目录（CB-022 模式一）：我方 device_id ↔ 将邑 sn/identifier 三元组 + token 吊销状态，device_info 不动';
