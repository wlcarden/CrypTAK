FROM python:3.12-slim

WORKDIR /app

# Dependencies first (layer cache — only rebuilds when requirements change)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Application code + default config
COPY src/ ./src/
COPY config.yaml .

# Non-root user
RUN useradd --system --no-create-home tracker
USER tracker

# Config path — mount a custom config.yaml at /app/config.yaml via docker-compose volume
CMD ["python", "-m", "src.main"]
