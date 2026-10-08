# NextStep — an AI operator for a senior's smartphone

**Team Northstar · Google Cloud AI Builder Cup 2026 (JAPAC)**
Theme: *Sustainability & Social Impact* (accessibility / digital inclusion)

NextStep is a floating button on an elderly person's Android phone. They say what they want
("Order one litre of milk on Blinkit") and give consent once. NextStep then **does it**: it plans,
opens apps, taps, types and scrolls, and pauses only at protected or irreversible steps.

```
User gives task + consent → NextStep plans → acts autonomously on the phone
      → pauses at passwords / OTPs / biometrics / payment PINs / final Send-Pay-Order
      → user confirms by voice → NextStep finishes the task
```

## Safety boundary
- Never types or reads passwords, OTPs, PINs or biometrics; the user does those privately.
- Asks for spoken confirmation before irreversible actions (send, place order, pay, delete).
- Scam shield: analyses suspicious messages/links and offers the official page instead.
- "Take me out": instantly returns to a safe screen.
- Every action is logged and visible to a trusted family member.

## Planned architecture (Google Cloud)
| Layer | Tech |
|---|---|
| Phone client | Kotlin Android app: overlay floating button + AccessibilityService (screen tree, gestures) + MediaProjection screenshots |
| Voice | Gemini Live API (low-latency, multilingual speech in and out) |
| Agent brain | Gemini Computer Use (mobile environment) + Gemini Flash planner, built with Google ADK |
| Backend | Python agent service on **Cloud Run** |
| Data | Firestore (sessions, action logs, family links), Firebase Auth, FCM |
| Family dashboard | Web app on Firebase Hosting |
| Observability | Cloud Logging / Trace |

## Repo layout
- `android/` — Android client
- `backend/` — agent service (Cloud Run)
- `docs/` — deck, architecture and demo script

## Submission checklist (deadline 18 Oct 2026)
- [ ] Live deployed URL (Cloud Run / Firebase)
- [ ] Demo video under 3 minutes
- [ ] Public GitHub repo
- [ ] Deck PDF (problem, architecture, business case, scale)
