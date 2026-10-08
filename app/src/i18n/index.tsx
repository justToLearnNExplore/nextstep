import * as SecureStore from 'expo-secure-store';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import en from './locales/en.json';
import hi from './locales/hi.json';
import kn from './locales/kn.json';

export type Strings = typeof en;
export type StringKey = { [K in keyof Strings]: Strings[K] extends string ? K : never }[keyof Strings];

/**
 * Language registry. To add a language: drop `<code>.json` (same keys as en.json) into
 * ./locales and add one entry here. The native overlay, voice (STT/TTS) and the backend all key
 * off `tag`, so nothing else needs to change.
 */
export const LANGUAGES = [
  { code: 'en', tag: 'en-IN', nativeName: 'English', strings: en },
  { code: 'hi', tag: 'hi-IN', nativeName: 'हिंदी', strings: hi },
  { code: 'kn', tag: 'kn-IN', nativeName: 'ಕನ್ನಡ', strings: kn },
] as const satisfies readonly { code: string; tag: string; nativeName: string; strings: Strings }[];

export type LanguageCode = (typeof LANGUAGES)[number]['code'];

const STORE_KEY = 'nextstep.language';

export function languageFor(code: string) {
  return LANGUAGES.find((l) => l.code === code) ?? LANGUAGES[0];
}

/** Strings the native overlay needs (it can't read JS): labels plus yes/no words. */
export function overlayLabels(code: string): Record<string, string | string[]> {
  return { ...languageFor(code).strings };
}

type Ctx = {
  lang: (typeof LANGUAGES)[number];
  setLanguage: (code: LanguageCode) => void;
  t: (key: StringKey) => string;
  ready: boolean;
};

const LanguageContext = createContext<Ctx | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [code, setCode] = useState<LanguageCode>('en');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    SecureStore.getItemAsync(STORE_KEY)
      .then((saved) => saved && setCode(languageFor(saved).code))
      .finally(() => setReady(true));
  }, []);

  const setLanguage = useCallback((next: LanguageCode) => {
    setCode(next);
    SecureStore.setItemAsync(STORE_KEY, next).catch(() => {});
  }, []);

  const value = useMemo<Ctx>(() => {
    const lang = languageFor(code);
    return {
      lang,
      setLanguage,
      ready,
      t: (key) => (lang.strings[key] as string) ?? (en[key] as string) ?? key,
    };
  }, [code, ready, setLanguage]);

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useI18n() {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error('useI18n must be used inside LanguageProvider');
  return ctx;
}
