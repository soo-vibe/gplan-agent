FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY . .

# Run as non-root for defense-in-depth even though Cloud Run already sandboxes.
RUN useradd --create-home --uid 1000 app \
    && chown -R app:app /app
USER app

ENV PORT=8080
EXPOSE 8080

# 1 worker × 8 threads matches Cloud Run --concurrency 8. Each request can
# spend 1-3s in Anthropic + Google API calls, so we trade throughput for
# memory headroom rather than spinning up more workers.
CMD exec gunicorn --bind :$PORT --workers 1 --threads 8 --timeout 120 app:app
