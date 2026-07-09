# syntax=docker/dockerfile:1
# vision-service：默认仅装基础依赖（MOCK_ENABLED=true，E2E/Compose 够用）
# 需要真实 YOLO/阿里云时：docker build --build-arg INSTALL_ML=true ...

FROM python:3.11-slim
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_DEFAULT_TIMEOUT=120

ARG INSTALL_ML=false

COPY vision-service/requirements-base.txt vision-service/requirements-ml.txt ./

RUN pip install --upgrade pip setuptools wheel \
    && pip install --no-cache-dir --retries 5 -r requirements-base.txt

# ultralytics 会拉取数百 MB 的 torch，网络不稳时易 zlib 解压失败；mock 模式无需安装
RUN if [ "$INSTALL_ML" = "true" ]; then \
      apt-get update && apt-get install -y --no-install-recommends \
        libglib2.0-0 libgl1 libxcb1 \
      && rm -rf /var/lib/apt/lists/* \
      && pip install --no-cache-dir --retries 5 \
        torch torchvision --index-url https://download.pytorch.org/whl/cpu \
      && pip install --no-cache-dir --retries 5 -r requirements-ml.txt \
      && pip uninstall -y opencv-python 2>/dev/null || true \
      && pip install --no-cache-dir --force-reinstall --no-deps opencv-python-headless==4.10.0.84 \
      && mkdir -p /app/models \
      && python -c "from ultralytics import YOLO; import shutil; from pathlib import Path; m=YOLO('yolov8n.pt'); src=Path(str(getattr(m,'ckpt_path','yolov8n.pt'))); dst=Path('/app/models/yolov8n.pt'); shutil.copy2(src if src.exists() else Path('yolov8n.pt'), dst) if (src.exists() or Path('yolov8n.pt').exists()) else None; print('model', dst.exists(), dst.stat().st_size if dst.exists() else 0)"; \
    fi

ENV YOLO_MODEL_PATH=/app/models/yolov8n.pt

COPY vision-service/app ./app

EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
