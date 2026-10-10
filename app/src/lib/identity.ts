import Constants from 'expo-constants';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

import { configureAgent } from './agent';

/**
 * Account without login: the senior only says their name. We create an anonymous Firebase
 * account bound to this phone (Firebase Auth REST, no UI) and attach the name to it. If no
 * Firebase key is configured (local development), a random device id is used instead.
 */
type Identity = {
  name: string;
  deviceId: string;
  uid?: string;
  idToken?: string;
  refreshToken?: string;
  expiresAt?: number; // epoch ms
};

const KEY = 'nextstep.identity';
const FIREBASE_API_KEY = (Constants.expoConfig?.extra?.firebaseApiKey as string | undefined) || '';
const REFRESH_MARGIN_MS = 5 * 60_000;

let cached: Identity | null = null;

async function load(): Promise<Identity | null> {
  if (cached) return cached;
  const raw = await SecureStore.getItemAsync(KEY);
  cached = raw ? (JSON.parse(raw) as Identity) : null;
  return cached;
}

async function persist(id: Identity) {
  cached = id;
  await SecureStore.setItemAsync(KEY, JSON.stringify(id));
  // The native agent calls the backend on its own (from the overlay), so it needs the same identity.
  configureAgent({
    deviceId: id.deviceId,
    authToken: id.idToken,
    refreshToken: id.refreshToken,
    tokenExpiresAt: id.expiresAt,
    firebaseApiKey: FIREBASE_API_KEY || undefined,
  });
}

async function firebase<T>(url: string, body: Record<string, unknown>): Promise<T> {
  const res = await fetch(`${url}?key=${FIREBASE_API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Firebase ${res.status}`);
  return (await res.json()) as T;
}

/** Creates the account on first run, or renames it. Safe to call again. */
export async function createAccount(name: string): Promise<Identity> {
  const existing = await load();
  const id: Identity = existing ?? { name, deviceId: Crypto.randomUUID().replaceAll('-', '') };
  id.name = name;

  if (FIREBASE_API_KEY) {
    if (!id.refreshToken) {
      const r = await firebase<{ idToken: string; refreshToken: string; expiresIn: string; localId: string }>(
        'https://identitytoolkit.googleapis.com/v1/accounts:signUp',
        { returnSecureToken: true },
      );
      Object.assign(id, { uid: r.localId, idToken: r.idToken, refreshToken: r.refreshToken, expiresAt: Date.now() + Number(r.expiresIn) * 1000 });
    }
    const token = await freshToken(id);
    await firebase('https://identitytoolkit.googleapis.com/v1/accounts:update', { idToken: token, displayName: name });
  }
  await persist(id);
  return id;
}

async function freshToken(id: Identity): Promise<string | undefined> {
  if (!FIREBASE_API_KEY || !id.refreshToken) return undefined;
  if (id.idToken && id.expiresAt && id.expiresAt - Date.now() > REFRESH_MARGIN_MS) return id.idToken;
  const res = await fetch(`https://securetoken.googleapis.com/v1/token?key=${FIREBASE_API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(id.refreshToken)}`,
  });
  if (!res.ok) throw new Error(`Token refresh ${res.status}`);
  const r = (await res.json()) as { id_token: string; refresh_token: string; expires_in: string };
  Object.assign(id, { idToken: r.id_token, refreshToken: r.refresh_token, expiresAt: Date.now() + Number(r.expires_in) * 1000 });
  await persist(id);
  return id.idToken;
}

/** Headers identifying this senior to the backend. */
export async function authHeaders(): Promise<Record<string, string>> {
  const id = await load();
  if (!id) return {};
  const token = await freshToken(id);
  return token ? { Authorization: `Bearer ${token}` } : { 'X-Device-Id': id.deviceId };
}

export async function getIdentity(): Promise<Identity | null> {
  return load();
}

/** Re-sends the identity to the native agent (e.g. after an app restart). */
export async function syncIdentityToNative() {
  const id = await load();
  if (id) await persist(id);
}

/**
 * Pulls the name out of what was said: "My name is Kamala" → "Kamala".
 * Prefixes/suffixes come from the language pack so new languages need no code.
 */
export function extractName(heard: string, prefixes: readonly string[], suffixes: readonly string[]): string {
  let s = heard.trim().replace(/[.!?।,]+$/u, '');
  for (const p of prefixes) {
    const i = s.toLowerCase().indexOf(p.toLowerCase());
    if (i !== -1) s = s.slice(i + p.length).trim();
  }
  for (const x of suffixes) {
    if (s.toLowerCase().endsWith(x.toLowerCase())) s = s.slice(0, -x.length).trim();
  }
  s = s.replace(/[.!?।,]+$/u, '').trim();
  // Title-case Latin script; leave Devanagari/Kannada as spoken.
  return s.replace(/\b([a-z])/g, (c) => c.toUpperCase()).slice(0, 40);
}
