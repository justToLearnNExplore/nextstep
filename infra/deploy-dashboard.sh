#!/usr/bin/env bash
# Builds the family dashboard and deploys it to Firebase Hosting. /api/** is forwarded to the
# Cloud Run backend (see firebase.json), so the dashboard and API share one origin.
# Prereqs (once): npx firebase-tools login; add Firebase to the GCP project in the console.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project)}"

npm --prefix "$ROOT/dashboard" ci
npm --prefix "$ROOT/dashboard" run build
(cd "$ROOT" && npx --yes firebase-tools deploy --only hosting --project "$PROJECT_ID")
echo "Dashboard: https://$PROJECT_ID.web.app"
