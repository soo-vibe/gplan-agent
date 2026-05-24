#!/usr/bin/env bash
# Cloud Run deployment for the stateless parser-only Planna backend.
#
# Architecture: the Android app holds the user's Google credentials and
# writes events directly to Google Calendar. This service is just a parser:
# accepts a text message + Google ID token, returns parsed schedule JSON.
# No Firestore, no OAuth flow, no per-user state.
#
# ----------------------------------------------------------------------------
# ONE-TIME SETUP
# ----------------------------------------------------------------------------
#
# 1. gcloud auth login && gcloud config set project YOUR_PROJECT_ID
#
# 2. Enable APIs:
#      gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
#        secretmanager.googleapis.com artifactregistry.googleapis.com \
#        iam.googleapis.com
#
# 3. Anthropic key in Secret Manager (only secret this service needs):
#      gcloud secrets create anthropic-api-key --replication-policy=automatic
#      printf '%s' "$ANTHROPIC_API_KEY" | gcloud secrets versions add anthropic-api-key --data-file=-
#
# 4. Dedicated runtime service account (no broad IAM — this service has no
#    Firestore/Calendar/Gmail access; just needs to read its own secret):
#      RUNTIME_SA="planna-run@${PROJECT}.iam.gserviceaccount.com"
#      gcloud iam service-accounts create planna-run \
#        --display-name="Planna Cloud Run runtime"
#      gcloud secrets add-iam-policy-binding anthropic-api-key \
#        --member="serviceAccount:${RUNTIME_SA}" \
#        --role=roles/secretmanager.secretAccessor
#
# 5. (One-time cleanup of legacy secrets / cron / IAM bindings — see
#    "MIGRATION CLEANUP" at the bottom.)
#
# ----------------------------------------------------------------------------
# DEPLOY
# ----------------------------------------------------------------------------

set -euo pipefail

REGION="${REGION:-asia-northeast3}"
SERVICE="${SERVICE:-planna}"
PROJECT="$(gcloud config get-value project)"
RUNTIME_SA="${RUNTIME_SA:-planna-run@${PROJECT}.iam.gserviceaccount.com}"

# Web OAuth client ID is the audience that ID tokens from the Android app
# must claim. Not sensitive — embedded in the APK — so plain env var, not
# a secret.
WEB_CLIENT_ID="${GOOGLE_WEB_CLIENT_ID:-173551063984-m29tuhrfhj3c95efl702m3qcmq62u2gg.apps.googleusercontent.com}"

gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --service-account "$RUNTIME_SA" \
  --ingress all \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 4 \
  --concurrency 8 \
  --timeout 60 \
  --set-secrets "ANTHROPIC_API_KEY=anthropic-api-key:latest" \
  --set-env-vars "GOOGLE_WEB_CLIENT_ID=${WEB_CLIENT_ID}"

URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
echo
echo "Deployed: $URL"

# ----------------------------------------------------------------------------
# MIGRATION CLEANUP (safe to run once after this deploy is stable)
# ----------------------------------------------------------------------------
#
#   # The Gmail polling cron is no longer needed (notification listener handles
#   # mail capture client-side):
#   gcloud scheduler jobs delete gmail-check-all --location="$REGION" --quiet || true
#
#   # Legacy secrets used by the old OAuth/Firestore flow:
#   gcloud secrets delete google-oauth-client --quiet || true
#   gcloud secrets delete oauth-state-secret  --quiet || true
#   gcloud secrets delete scheduler-secret    --quiet || true
#   gcloud secrets delete admin-secret        --quiet || true
#
#   # Firestore database can be wiped via the Firebase console once you've
#   # confirmed nothing else in this project uses it. Documents in users,
#   # pending_users, processed_messages, user_events are all orphaned now.
#
#   # Runtime SA no longer needs roles/datastore.user — drop the binding:
#   gcloud projects remove-iam-policy-binding "$PROJECT" \
#     --member="serviceAccount:${RUNTIME_SA}" --role=roles/datastore.user || true
