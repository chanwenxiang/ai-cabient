# datasets/

历史柜内 SKU 数据集目录占位。

**现状**：云端自研 YOLO 训练链路已移除；本目录不再作为生产训练入口。开发联调使用 mock 识别；生产识别由端侧提供方完成，见 [`docs/VISION_QUECTEL_INTEGRATION.md`](../docs/VISION_QUECTEL_INTEGRATION.md)。

若后续需要端侧模型供应商的标注规范，在厂商合同与对接文档中维护，勿在本仓恢复已删除的 `train_sku_yolo` / `verify-vision-model` 路径。
