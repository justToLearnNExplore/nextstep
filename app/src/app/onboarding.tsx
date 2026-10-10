import { router } from 'expo-router';
import * as SecureStore from 'expo-secure-store';
import { useCallback, useEffect, useState } from 'react';
import { AppState, PermissionsAndroid, ScrollView, StyleSheet, View } from 'react-native';

import { NameCapture } from '../components/NameCapture';
import { BigButton, Body, LanguageChips, Logo, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { configureAgent, NextStepAgent } from '../lib/agent';
import { colors, size } from '../theme';
import { ONBOARDED_KEY } from './index';

type Step = 'language' | 'welcome' | 'mic' | 'name' | 'accessibility' | 'notifications';
// Microphone comes before the name so the senior can simply say it.
const ORDER: Step[] = ['language', 'welcome', 'mic', 'name', 'accessibility', 'notifications'];

/**
 * One idea per screen, with a plain-language disclosure of exactly what each permission allows
 * and what NextStep will never do. Every optional step can be skipped.
 */
export default function Onboarding() {
  const { t } = useI18n();
  const [step, setStep] = useState<Step>('language');
  const [a11yOn, setA11yOn] = useState(() => NextStepAgent.isAccessibilityEnabled());
  const [notifOn, setNotifOn] = useState(() => NextStepAgent.isNotificationAccessEnabled());

  // Re-check after the user returns from Android Settings.
  const refresh = useCallback(() => {
    setA11yOn(NextStepAgent.isAccessibilityEnabled());
    setNotifOn(NextStepAgent.isNotificationAccessEnabled());
  }, []);
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => s === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  const next = () => setStep(ORDER[Math.min(ORDER.indexOf(step) + 1, ORDER.length - 1)]);

  const finish = async (scamCheck: boolean) => {
    configureAgent({ scamCheckEnabled: scamCheck });
    await SecureStore.setItemAsync(ONBOARDED_KEY, '1');
    router.replace('/talk');
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Logo size={72} />
        {step === 'language' && (
          <>
            <Title>{t('chooseLanguage')}</Title>
            <LanguageChips />
            <View style={styles.spacer} />
            <BigButton label={t('next')} icon="arrow-right" onPress={next} />
          </>
        )}

        {step === 'welcome' && (
          <>
            <Title>{t('welcomeTitle')}</Title>
            <Body>{t('welcomeBody')}</Body>
            <View style={styles.spacer} />
            <BigButton label={t('next')} icon="arrow-right" onPress={next} />
          </>
        )}

        {step === 'accessibility' && (
          <>
            <Title>{t('permAccessibilityTitle')}</Title>
            <Disclosure text={t('permAccessibilityWhy')} />
            <Body>{t('permAccessibilitySteps')}</Body>
            <View style={styles.spacer} />
            {a11yOn ? (
              <BigButton label={t('next')} tone="go" icon="check" onPress={next} />
            ) : (
              <BigButton label={t('allow')} icon="cellphone-cog" onPress={() => NextStepAgent.openAccessibilitySettings()} />
            )}
          </>
        )}

        {step === 'name' && <NameCapture onDone={next} />}

        {step === 'mic' && (
          <>
            <Title>{t('permMicTitle')}</Title>
            <Disclosure text={t('permMicWhy')} />
            <View style={styles.spacer} />
            <BigButton
              label={t('allow')}
              icon="microphone"
              onPress={async () => {
                await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
                next();
              }}
            />
            <BigButton label={t('skip')} tone="outline" onPress={next} />
          </>
        )}

        {step === 'notifications' && (
          <>
            <Title>{t('permNotifTitle')}</Title>
            <Disclosure text={t('permNotifWhy')} />
            <View style={styles.spacer} />
            {notifOn ? (
              <BigButton label={t('finish')} tone="go" icon="check" onPress={() => finish(true)} />
            ) : (
              <>
                <BigButton label={t('allow')} icon="shield-check" onPress={() => NextStepAgent.openNotificationAccessSettings()} />
                <BigButton label={t('skip')} tone="outline" onPress={() => finish(false)} />
              </>
            )}
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

function Disclosure({ text }: { text: string }) {
  return (
    <View style={styles.disclosure}>
      <Body>{text}</Body>
    </View>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 32, gap: 20 },
  spacer: { height: 8 },
  disclosure: { backgroundColor: colors.amber, borderRadius: size.radius, padding: 16 },
});
