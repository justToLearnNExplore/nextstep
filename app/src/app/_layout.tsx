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
import { colors } from '../theme';

/** Keeps the native agent (overlay, voice, backend calls) in the user's language. */
function NativeSync() {
  const { lang, ready } = useI18n();
  useAgentEventCollector();
  useEffect(() => {
    if (ready) configureAgent({ language: lang.tag, labels: overlayLabels(lang.code) });
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
