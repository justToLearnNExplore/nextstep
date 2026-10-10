FROM python:3.14-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080
WORKDIR /srv
COPY requirements.txt constraints.txt ./
RUN pip install --no-cache-dir -r requirements.txt -c constraints.txt
COPY app ./app
# Cloud Run sends SIGTERM; uvicorn handles graceful shutdown.
CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1
