-- recognition_result.model_version 列宽 32 -> 64
--
-- 背景：recognition_result 自 V1__init_schema.sql 建表起长期没有写入者（建表即孤儿，全仓
-- java/xml 零引用、线上 0 行）。2026-09-19 补齐运行期写入（RecognitionResultWriter：Kafka 与
-- POST /internal/v1/vision/edge-results 两条通道在受理点统一落库）。
--
-- 为什么必须放宽：端侧固件/模型版本号实际可能超过 32 字符
-- （如 quectel-openvending-yolo-v8n-2026.09）。若维持 32，超长会在写库时被 PostgreSQL 拒收；
-- 而被 best-effort 调用方吞掉后，现象是「结算成功但识别结果静默缺失」——正是本次要消除的断链。
-- 放宽后由入口（HTTP 400）与落库两侧共用同一权威常量
-- （VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH = 64）显式拒绝，不静默。
--
-- 非热点表；varchar 加宽在 PostgreSQL 为元数据变更，不重写表、不长锁。
-- LOCK_RISK: low
-- TABLES: recognition_result (~0 rows)

ALTER TABLE recognition_result
    ALTER COLUMN model_version TYPE VARCHAR(64);
