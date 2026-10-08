import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { BigButton, Body, LanguageChips, Logo, Screen, TaskCard, Title } from '../components/ui';
import { type StringKey, useI18n } from '../i18n';
import { NextStepAgent } from '../lib/agent';
import { colors, size } from '../theme';

const CARDS: { label: StringKey; goal: StringKey; icon: 'basket' | 'pill' | 'music' | 'shield-check' }[] = [
  { label: 'cardGroceries', goal: 'goalGroceries', icon: 'basket' },
  { label: 'cardMedicinePhoto', goal: 'goalMedicinePhoto', icon: 'pill' },
  { label: 'cardBhajan', goal: 'goalBhajan', icon: 'music' },
  { label: 'cardCheckMessage', goal: 'goalCheckMessage', icon: 'shield-check' },
];

/**
 * "Talk to NextStep": picture shortcuts for common tasks plus one big Speak button.
 * Starting a task hands control to the native agent and steps NextStep out of the way;
 * the plan, confirmations and Stop button then live in the floating overlay.
 */
export default function Talk() {
  const { t, lang } = useI18n();
  const [status, setStatus] = useState<string | null>(null);
  const [listening, setListening] = useState(false);

  const start = (goal: string) => {
    if (!NextStepAgent.isAccessibilityEnabled()) {
      setStatus(t('serviceOff'));
      NextStepAgent.openAccessibilitySettings();
      return;
    }
    NextStepAgent.startTask(goal);
    NextStepAgent.minimizeApp();
  };

  // The medicine card goes straight to NextStep's guided camera; other cards start an agent task.
  const open = (c: (typeof CARDS)[number]) =>
    c.label === 'cardMedicinePhoto' ? router.push('/medicine?to=doctor') : start(t(c.goal));

  const speak = async () => {
    if (listening) {
      NextStepAgent.cancelListening();
      return;
    }
    setListening(true);
    setStatus(t('listening'));
    const heard = await NextStepAgent.listenOnce(lang.tag).finally(() => setListening(false));
    if (!heard) {
      setStatus(t('didNotHear'));
      return;
    }
    setStatus(`${t('youSaid')}: “${heard}”`);
    start(heard);
  };

  return (
    <Screen>
      <View style={styles.header}>
        <Logo size={48} />
        <View style={{ flex: 1 }} />
        <Pressable
          onPress={() => router.push('/settings')}
          accessibilityRole="button"
          accessibilityLabel={t('settings')}
          style={styles.iconButton}
        >
          <MaterialCommunityIcons name="cog" size={30} color={colors.ink} />
        </Pressable>
      </View>
      <LanguageChips />
      <View style={{ height: 16 }} />
      <Title>{t('talkTitle')}</Title>

      <View style={styles.grid}>
        <View style={styles.row}>
          {CARDS.slice(0, 2).map((c) => (
            <TaskCard key={c.label} label={t(c.label)} icon={c.icon} onPress={() => open(c)} />
          ))}
        </View>
        <View style={styles.row}>
          {CARDS.slice(2).map((c) => (
            <TaskCard key={c.label} label={t(c.label)} icon={c.icon} onPress={() => open(c)} />
          ))}
        </View>
      </View>

      {status ? <Body style={styles.status}>{status}</Body> : null}
      <View style={styles.footer}>
        <BigButton
          label={listening ? t('listening') : t('speak')}
          icon={listening ? 'microphone-off' : 'microphone'}
          tone={listening ? 'go' : 'primary'}
          tall
          onPress={speak}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12 },
  iconButton: { width: size.touch, height: size.touch, alignItems: 'center', justifyContent: 'center' },
  grid: { gap: 12, marginTop: 16 },
  row: { flexDirection: 'row', gap: 12 },
  status: { marginTop: 16, textAlign: 'center' },
  footer: { marginTop: 'auto', paddingBottom: 20 },
});
