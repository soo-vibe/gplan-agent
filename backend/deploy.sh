#!/usr/bin/env bash
# Cloud Run deployment for the multi-user GPlan Agent backend.
#
# ----------------------------------------------------------------------------
# ONE-TIME SETUP (do this manually before the first deploy)
# ----------------------------------------------------------------------------
#
# 1. gcloud auth login && gcloud config set project YOUR_PROJECT_ID
#
# 2. Enable APIs:
#      gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
#        secretmanager.googleapis.com cloudscheduler.googleapis.com \
#        artifactregistry.googleapis.com firestore.googleapis.com iam.googleapis.com
#
# 3. Create Firestore database (Native mode, asia-northeast3):
#      gcloud firestore databases create --location=asia-northeast3
#
# 4. In Google Cloud Console → APIs & Services → OAuth consent screen:
#      - User type: External, Publishing status: Testing
#      - Scopes: calendar, gmail.readonly, gmail.modify, openid, email, profile
#      - Add every friend's Gmail address to "Test users" (max 100)
#
# 5. In Google Cloud Console → Credentials → Create OAuth 2.0 Client ID:
#      - Application type: Web application
#      - Authorized redirect URIs:
#          https://gplan-agent-<HASH>.asia-northeast3.run.app/oauth/callback
#          http://localhost:8080/oauth/callback   (for local dev)
#      - Download JSON, save as backend/credentials_web.json (gitignored AND
#        dockerignored — never bake into the image; only Secret Manager).
#
# 6. Create Secret Manager secrets:
#      gcloud secrets create anthropic-api-key --replication-policy=automatic
#      printf '%s' "$ANTHROPIC_API_KEY" | gcloud secrets versions add anthropic-api-key --data-file=-
#
#      gcloud secrets create google-oauth-client --replication-policy=automatic
#      gcloud secrets versions add google-oauth-client --data-file=credentials_web.json
#
#      gcloud secrets create oauth-state-secret --replication-policy=automatic
#      printf '%s' "$(openssl rand -hex 32)" | gcloud secrets versions add oauth-state-secret --data-file=-
#
#      gcloud secrets create scheduler-secret --replication-policy=automatic
#      printf '%s' "$(openssl rand -hex 32)" | gcloud secrets versions add scheduler-secret --data-file=-
#
#      # Separate secret for /admin/* endpoints — never share with the cron job.
#      gcloud secrets create admin-secret --replication-policy=automatic
#      printf '%s' "$(openssl rand -hex 32)" | gcloud secrets versions add admin-secret --data-file=-
#
# 7. Create a dedicated runtime service account (do NOT use the default Compute
#    SA; it has roles/editor project-wide which is way too broad):
#      RUNTIME_SA="gplan-agent-run@${PROJECT}.iam.gserviceaccount.com"
#      gcloud iam service-accounts create gplan-agent-run \
#        --display-name="GPlan Agent Cloud Run runtime"
#
#      # Firestore data access only:
#      gcloud projects add-iam-policy-binding "$PROJECT" \
#        --member="serviceAccount:${RUNTIME_SA}" --role=roles/datastore.user
#
#      # Secret access — per secret, not project-wide:
#      for s in anthropic-api-key google-oauth-client oauth-state-secret scheduler-secret admin-secret; do
#        gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:${RUNTIME_SA}" --role=roles/secretmanager.secretAccessor
#      done
#
# ----------------------------------------------------------------------------
# DEPLOY
# ----------------------------------------------------------------------------
# First deploy: PUBLIC_BASE_URL is unknown. Run with PUBLIC_BASE_URL_OVERRIDE=""
# to deploy, then re-run to inject the discovered URL.

set -euo pipefail

REGION="${REGION:-asia-northeast3}"
SERVICE="${SERVICE:-gplan-agent}"
PROJECT="$(gcloud config get-value project)"
RUNTIME_SA="${RUNTIME_SA:-gplan-agent-run@${PROJECT}.iam.gserviceaccount.com}"

# Discover existing URL if the service was previously deployed.
EXISTING_URL=""
if gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)' >/dev/null 2>&1; then
  EXISTING_URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
fi

PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-$EXISTING_URL}"

EXTRA_ENV=""
if [ -n "$PUBLIC_BASE_URL" ]; then
  EXTRA_ENV=",PUBLIC_BASE_URL=${PUBLIC_BASE_URL}"
fi

# Notes on flags below:
#   --service-account: principle of least privilege; never use default Compute SA.
#   --ingress all: required because Android clients call this directly. If you
#     front it with a Load Balancer + Cloud Armor, switch to "internal-and-cloud-load-balancing".
#   --min-instances=1: avoids 5-minute idle Cloud Run cold starts that would
#     make the /gmail/check-all cron tick miss the 120s timeout under load.
#   --concurrency: keep ≤8 because gunicorn uses --threads 8 and each request
#     can spend 1-3s in Anthropic + Google API calls.
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --service-account "$RUNTIME_SA" \
  --ingress all \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 1 \
  --max-instances 4 \
  --concurrency 8 \
  --timeout 120 \
  --set-secrets "ANTHROPIC_API_KEY=anthropic-api-key:latest,GOOGLE_OAUTH_CLIENT=google-oauth-client:latest,OAUTH_STATE_SECRET=oauth-state-secret:latest,SCHEDULER_SECRET=scheduler-secret:latest,ADMIN_SECRET=admin-secret:latest" \
  --set-env-vars "GCP_PROJECT=${PROJECT}${EXTRA_ENV}"

URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
echo
echo "Deployed: $URL"
echo

if [ -z "$PUBLIC_BASE_URL" ]; then
  echo "First deploy detected. Re-run this script now so PUBLIC_BASE_URL is injected:"
  echo "  PUBLIC_BASE_URL=$URL ./deploy.sh"
  echo
  echo "Then add this redirect URI to your Web OAuth client:"
  echo "  $URL/oauth/callback"
  exit 0
fi

echo "Next steps:"
echo
echo "  1. (One-time) Replace the old gmail-check Cloud Scheduler job:"
echo "       gcloud scheduler jobs delete gmail-check --location=$REGION --quiet 2>/dev/null || true"
echo "       SECRET=\$(gcloud secrets versions access latest --secret=scheduler-secret)"
echo "       gcloud scheduler jobs create http gmail-check-all \\"
echo "         --location=$REGION \\"
echo "         --schedule='*/5 * * * *' \\"
echo "         --uri='$URL/gmail/check-all' \\"
echo "         --http-method=POST \\"
echo "         --headers=\"X-Scheduler-Secret=\$SECRET\""
echo
echo "  2. Update android/local.properties:"
echo "       schedule.baseUrl=$URL"
echo
echo "  3. Verify a stale 'schedule-agent' service isn't lingering:"
echo "       gcloud run services list --region=$REGION"
echo "     Delete it if found (it would still be allow-unauthenticated):"
echo "       gcloud run services delete schedule-agent --region=$REGION"
echo
echo "  4. After 1 week of stable operation, delete legacy secrets:"
echo "       gcloud secrets delete google-token-json --quiet"
echo "       gcloud secrets delete google-credentials-json --quiet"
echo "       gcloud secrets delete api-shared-secret --quiet"
echo
echo "  5. Admin endpoints (/admin/*) now require X-Admin-Secret (separate from"
echo "     X-Scheduler-Secret). Retrieve it for ad-hoc curl calls:"
echo "       gcloud secrets versions access latest --secret=admin-secret"
