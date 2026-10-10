import {
  AtkinsonHyperlegible_400Regular,
  AtkinsonHyperlegible_700Bold,
  useFonts,
} from '@expo-google-fonts/atkinson-hyperlegible';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';

import { LanguageProvider, overlayLabels, useI18n } from '../i18n';
import { configureAgent, useAgentEventCollector } from '../lib/agent';
import { put } from '../lib/api';
import { getIdentity, syncIdentityToNative } from '../lib/identity';
import { colors } from '../theme';

/** Keeps the native agent (overlay, voice, backend calls) in the user's language. */
function NativeSync() {
  const { lang, ready } = useI18n();
  useAgentEventCollector();
  useEffect(() => {
    if (ready) configureAgent({ language: lang.tag, labels: overlayLabels(lang.code) });
  }, [lang, ready]);
  // Keep the native agent's identity current and the server's copy of the name in sync
  // (covers onboarding while offline, and language changes).
  useEffect(() => {
    if (!ready) return;
    syncIdentityToNative()
      .then(getIdentity)
      .then((id) => id && put('/v1/me', { display_name: id.name, language: lang.tag }))
      .catch(() => {});
  }, [lang, ready]);
  return null;
}

export default function RootLayout() {
  const [fontsLoaded] = useFonts({ AtkinsonHyperlegible_400Regular, AtkinsonHyperlegible_700Bold });
  if (!fontsLoaded) return null;

  return (
    <LanguageProvider>
      <NativeSync />
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.paper } }} />
    </LanguageProvider>
  );
}
