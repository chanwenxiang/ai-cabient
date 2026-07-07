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
      pip install --no-cache-dir --retries 5 \
        torch torchvision --index-url https://download.pytorch.org/whl/cpu \
      && pip install --no-cache-dir --retries 5 -r requirements-ml.txt; \
    fi

COPY vision-service/app ./app

EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
