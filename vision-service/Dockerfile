FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY vision-service/requirements-base.txt /app/requirements-base.txt
RUN pip install --no-cache-dir -r /app/requirements-base.txt \
    && groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app \
    && chown -R app:app /app
COPY --chown=app:app vision-service/app /app/app
USER app
EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
