# 柜内商品 SKU 数据集

用于训练 **专用检测模型**（非 COCO 通用类）。FINAL §10 要求真实扣款前完成 ≥1000 次取放标注与准确率评估。

## 目录结构（YOLO 格式）

```
datasets/cabinet-skus-v1/
  images/
    train/          # 训练图片
    val/            # 验证图片
    test/           # 留作上线前回归
  labels/
    train/          # 与 images/train 同名的 .txt 标注
    val/
    test/
  meta/
    devices.json    # 柜机/摄像头元数据（可选）
    labeling-guide.md
```

## 标注规范

| 字段 | 说明 |
|------|------|
| `class_name` | 与 `training/data.yaml` 的 `names` 一致，如 `cola_330ml` |
| bbox | YOLO 归一化 `class cx cy w h` |
| 遮挡 | 可见面积 <30% 标为 difficult，训练时可降权 |
| 多商品 | 同一帧多个实例分别标注 |

**禁止** 用 COCO 类名（`bottle`/`cup`）训练后直接用于生产 SKU 结算。

## 采集建议

- 每台柜机 ≥200 张关门帧（含空柜、单件、多件、遮挡、反光）
- 覆盖 SKU 包装更替、价签遮挡、消费者手部遮挡
- 视频取中间帧或关键帧；与 `yolo_recognizer._prepare_inference_path` 策略对齐

## 隐私

- 人脸/手机号打码或裁剪
- 原始素材仅存内网对象存储，勿提交 Git

## 下一步

1. 标注完成后运行 `python training/train_sku_yolo.py`
2. 在运营后台「视觉映射」维护 `mapping_source=YOLO_SKU`
3. 执行 `scripts/verify-vision-model.ps1` 与 §10 准确率回归
