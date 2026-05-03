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
#        artifactregistry.googleapis.com firestore.googleapis.com
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
#          https://schedule-agent-<HASH>.asia-northeast3.run.app/oauth/callback
#          http://localhost:8080/oauth/callback   (for local dev)
#      - Download JSON, save as backend/credentials_web.json (gitignored)
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
# 7. Grant the Cloud Run runtime service account access:
#      PROJECT_NUMBER=$(gcloud projects describe "$(gcloud config get-value project)" --format='value(projectNumber)')
#      SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
#      for s in anthropic-api-key google-oauth-client oauth-state-secret scheduler-secret; do
#        gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:${SA}" --role=roles/secretmanager.secretAccessor
#      done
#      gcloud projects add-iam-policy-binding "$(gcloud config get-value project)" \
#        --member="serviceAccount:${SA}" --role=roles/datastore.user
#
# ----------------------------------------------------------------------------
# DEPLOY
# ----------------------------------------------------------------------------
# First deploy: PUBLIC_BASE_URL is unknown. Run with PUBLIC_BASE_URL_OVERRIDE=""
# to deploy, then re-run to inject the discovered URL.

set -euo pipefail

REGION="${REGION:-asia-northeast3}"
SERVICE="${SERVICE:-schedule-agent}"
PROJECT="$(gcloud config get-value project)"

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

gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 2 \
  --timeout 120 \
  --set-secrets "ANTHROPIC_API_KEY=anthropic-api-key:latest,GOOGLE_OAUTH_CLIENT=google-oauth-client:latest,OAUTH_STATE_SECRET=oauth-state-secret:latest,SCHEDULER_SECRET=scheduler-secret:latest" \
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
echo "       (schedule.apiKey is no longer used — remove it)"
echo
echo "  3. After 1 week of stable operation, delete legacy secrets:"
echo "       gcloud secrets delete google-token-json --quiet"
echo "       gcloud secrets delete google-credentials-json --quiet"
echo "       gcloud secrets delete api-shared-secret --quiet"
