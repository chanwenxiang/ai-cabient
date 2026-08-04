# 开门柜顶摄 Delta 数据采集规范

> 面向 **AI 智能开门柜**（Grab&Go），非弹簧机货道场景。  
> 训练目标：`cabinet-skus-v1.0.0.pt`，配合 `YOLO_RECOGNITION_MODE=delta`。

## 1. 采集原则

| 项 | 要求 |
|----|------|
| 摄像头 | 顶摄 1～2 路，720p+，固定曝光 |
| 触发 | 每次购物会话 **开门前 1 帧 + 关门后 1 帧**（delta 主路径） |
| 标注 | class 名与 `sku_catalog.yolo_class_name` 一致 |
| 每 SKU | 试点柜至少 **200 组** before/after（含不同光照、摆放角度） |
| 负样本 | 空柜、只开门未取、遮挡、多人手 |

## 2. 目录结构

```
vision-service/datasets/cabinet-skus-v1/
  raw/
    sessions/          # 原始 open/close 视频或帧对
      {session_id}/
        open_top.jpg
        close_top.jpg
        meta.json      # deviceId, sku ground truth（补货/订单）
  images/train|val|test
  labels/train|val|test
  meta/
    labeling-guide.md
    collection-stats.json
```

## 3. 自动化脚本

```powershell
# 从 trade catalog 拉 class 映射并生成合成 + MinIO 会话样本
cd ai-cabinet/vision-service
python scripts/collect_sku_dataset.py --per-class 80 --minio

# 可选：下载 HoloSelecta / RPC 预训练权重作迁移学习起点
python scripts/download_holoselecta_pretrained.py

# 训练 cabinet-skus-v1
cd training
python train_sku_yolo.py --data data.yaml --epochs 80 --name cabinet-skus-v1
```

产物复制到 `vision-service/models/cabinet-skus-v1.0.0.pt`，并通过 `scripts/verify-vision-model.ps1` 门禁。

## 4. 预训练 → 微调

1. **HoloSelecta / RPC**：通用零售检测预训练（见 `scripts/download_holoselecta_pretrained.py`）
2. **自有 delta 微调**：仅替换最后一层 class head，保留 backbone
3. **禁止**以弹簧机公开货道图作为主训练集

## 5. 验收

- 单 SKU 识别准确率 ≥ 95%（试点柜千次取放，见 `FINAL_END_TO_END_TEST_PLAN.md`）
- `vision_enrollment_status=PRODUCTION` 且设备库存白名单校验通过后才自动扣款
