# backend/ai-services/price_saving_agent/Dockerfile
FROM python:3.12-slim

WORKDIR /app

# psycopg/asyncpg gibi paketlerin derleme bağımlılıkları için gerekli
# minimal build araçları; imaj boyutunu küçük tutmak için tek RUN'da
# kurulup temizleniyor.
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc g++ \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
