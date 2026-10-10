#!/usr/bin/env bash
# Deploys the NextStep backend to Cloud Run with the Gemini key from Secret Manager.
# Prereqs (once): gcloud auth login; gcloud config set project <PROJECT_ID>
#   gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
#     secretmanager.googleapis.com firestore.googleapis.com
#   gcloud firestore databases create --location=asia-south1
#   printf '%s' "$GEMINI_API_KEY" | gcloud secrets create gemini-api-key --data-file=-
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project)}"
REGION="${REGION:-asia-south1}"
SERVICE="${SERVICE:-nextstep-api}"

gcloud run deploy "$SERVICE" \
  --project "$PROJECT_ID" \
  --region "$REGION" \
  --source "$(dirname "$0")/../backend" \
  --allow-unauthenticated \
  --set-secrets "GOOGLE_API_KEY=gemini-api-key:latest" \
  --set-env-vars "NEXTSTEP_STORE=firestore,GOOGLE_CLOUD_PROJECT=$PROJECT_ID,NEXTSTEP_REQUIRE_AUTH=${REQUIRE_AUTH:-false},NEXTSTEP_DASHBOARD_URL=https://$PROJECT_ID.web.app" \
  --memory 1Gi --cpu 1 --timeout 120 --min-instances 0 --max-instances 5

gcloud run services describe "$SERVICE" --project "$PROJECT_ID" --region "$REGION" --format 'value(status.url)'
