# syntax=docker/dockerfile:1
# vision-service：开发 mock + 端侧识别对接占位（无自研 YOLO）

FROM python:3.11-slim
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_DEFAULT_TIMEOUT=300
ENV PIP_RETRIES=10
ENV RECOGNIZER_BACKEND=mock
ENV MOCK_ENABLED=true

COPY vision-service/requirements-base.txt ./

RUN pip install --upgrade pip setuptools wheel \
    && pip install --no-cache-dir --retries 10 --timeout 300 -r requirements-base.txt

COPY vision-service/app ./app

EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
