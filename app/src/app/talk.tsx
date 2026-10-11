import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router, type Href } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { MicButton } from '../components/MicButton';
import { Body, Logo, Screen } from '../components/ui';
import { type StringKey, useI18n } from '../i18n';
import { startGoal } from '../lib/agent';
import { getIdentity } from '../lib/identity';
import { colors, fonts, size } from '../theme';

type Icon = 'basket' | 'pill' | 'car' | 'cellphone-cog';
const HELPERS: { label: StringKey; icon: Icon; href: Href }[] = [
  { label: 'homeGroceries', icon: 'basket', href: '/grocery' },
  { label: 'homeMedicine', icon: 'pill', href: '/medicine?to=doctor' },
  { label: 'homeRide', icon: 'car', href: '/ride' },
  { label: 'homePhone', icon: 'cellphone-cog', href: '/phone' },
];

/**
 * Home: a greeting, one big mic for anything, and four helpers for the most common needs.
 * Language lives in Settings. Starting a task steps NextStep out of the way; the plan,
 * confirmations and Stop then live in the floating bubble.
 */
export default function Home() {
  const { t } = useI18n();
  const [name, setName] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    getIdentity().then((id) => setName(id?.name || null));
  }, []);

  const start = async (goal: string) => {
    if (!(await startGoal(goal))) setStatus(t('serviceOff'));
  };

  return (
    <Screen>
      <View style={styles.header}>
        <Logo size={44} />
        <Pressable onPress={() => router.push('/settings')} accessibilityRole="button" accessibilityLabel={t('settings')} style={styles.iconButton}>
          <MaterialCommunityIcons name="cog" size={30} color={colors.ink} />
        </Pressable>
      </View>

      <Text style={styles.greeting} accessibilityRole="header">
        {name ? t('greetingNamed').replace('{name}', name) : t('greetingPlain')}
      </Text>

      <View style={styles.center}>
        <MicButton onHeard={start} label={t('tapAndSpeak')} />
        {status ? <Body style={{ textAlign: 'center' }}>{status}</Body> : null}
      </View>

      <View style={styles.grid}>
        {[0, 2].map((i) => (
          <View key={i} style={styles.row}>
            {HELPERS.slice(i, i + 2).map((h) => (
              <Pressable
                key={h.label}
                onPress={() => router.push(h.href)}
                accessibilityRole="button"
                accessibilityLabel={t(h.label)}
                style={({ pressed }) => [styles.tile, pressed && { backgroundColor: colors.amber }]}
              >
                <MaterialCommunityIcons name={h.icon} size={34} color={colors.ink} />
                <Text style={styles.tileText}>{t(h.label)}</Text>
              </Pressable>
            ))}
          </View>
        ))}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 10 },
  iconButton: { width: size.touch, height: size.touch, alignItems: 'center', justifyContent: 'center' },
  greeting: { fontFamily: fonts.bold, fontSize: 28, lineHeight: 36, color: colors.ink, textAlign: 'center', marginTop: 8 },
  center: { flex: 1, justifyContent: 'center', gap: 12 },
  grid: { gap: 12, paddingBottom: 20 },
  row: { flexDirection: 'row', gap: 12 },
  tile: {
    flex: 1,
    minHeight: 96,
    borderRadius: 18,
    borderWidth: 2,
    borderColor: colors.ink,
    backgroundColor: colors.white,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    padding: 8,
  },
  tileText: { fontFamily: fonts.bold, fontSize: 19, color: colors.ink, textAlign: 'center' },
});
