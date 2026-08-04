FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY vision-service/requirements-base.txt /app/requirements-base.txt
RUN pip install --no-cache-dir -r /app/requirements-base.txt
COPY vision-service/app /app/app
EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
