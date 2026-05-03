# Pinned by digest for supply-chain integrity (the floating `3.12-slim` tag
# can move silently). To refresh, run:
#   curl -sI -H "Accept: application/vnd.oci.image.index.v1+json" \
#     -H "Authorization: Bearer $(curl -s 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull' | jq -r .token)" \
#     https://registry-1.docker.io/v2/library/python/manifests/3.12-slim \
#     | grep -i docker-content-digest
FROM python:3.14-slim@sha256:5b3879b6f3cb77e712644d50262d05a7c146b7312d784a18eff7ff5462e77033

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
