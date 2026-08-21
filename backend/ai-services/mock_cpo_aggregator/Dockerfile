# backend/ai-services/mock_cpo_aggregator/Dockerfile
# Yalnızca yerel geliştirme/CI ortamı içindir — production'da bu servis
# gerçek CPO Aggregator sözleşmesiyle DEĞİŞTİRİLİR, docker-compose.yml'e
# dahil edilmez.
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .

EXPOSE 9999
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9999"]
