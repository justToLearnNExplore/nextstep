import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ComponentProps, ReactNode } from 'react';
import { Image, Pressable, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { LANGUAGES, useI18n } from '../i18n';
import { colors, fonts, size } from '../theme';

type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];
type Tone = 'primary' | 'go' | 'stop' | 'outline';

const TONES: Record<Tone, { bg: string; fg: string; border?: string }> = {
  primary: { bg: colors.ink, fg: colors.white },
  go: { bg: colors.go, fg: colors.white },
  stop: { bg: colors.stop, fg: colors.white },
  outline: { bg: colors.white, fg: colors.ink, border: colors.ink },
};

export function Screen({ children, style }: { children: ReactNode; style?: ViewStyle }) {
  return <SafeAreaView style={[styles.screen, style]}>{children}</SafeAreaView>;
}

export function Logo({ size: s = 56 }: { size?: number }) {
  return (
    <Image
      source={require('../../assets/logo.png')}
      style={{ width: s, height: s }}
      accessibilityIgnoresInvertColors
      accessible={false}
    />
  );
}

export function Title({ children }: { children: ReactNode }) {
  return (
    <Text style={styles.title} accessibilityRole="header">
      {children}
    </Text>
  );
}

export function Body({ children, style }: { children: ReactNode; style?: object }) {
  return <Text style={[styles.body, style]}>{children}</Text>;
}

export function BigButton({
  label,
  onPress,
  tone = 'primary',
  icon,
  tall,
}: {
  label: string;
  onPress: () => void;
  tone?: Tone;
  icon?: IconName;
  tall?: boolean;
}) {
  const t = TONES[tone];
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: t.bg, minHeight: tall ? 88 : size.touch },
        t.border ? { borderWidth: 2, borderColor: t.border } : null,
        pressed && { opacity: 0.85, transform: [{ scale: 0.98 }] },
      ]}
    >
      {icon ? <MaterialCommunityIcons name={icon} size={tall ? 34 : 28} color={t.fg} /> : null}
      <Text style={[styles.buttonText, { color: t.fg }]}>{label}</Text>
    </Pressable>
  );
}

/** Picture card: icon + short label. Tapping starts a task, so labels are verbs. */
export function TaskCard({ label, icon, onPress }: { label: string; icon: IconName; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => [styles.card, pressed && { backgroundColor: colors.amber }]}
    >
      <MaterialCommunityIcons name={icon} size={48} color={colors.ink} />
      <Text style={styles.cardText}>{label}</Text>
    </Pressable>
  );
}

export function LanguageChips() {
  const { lang, setLanguage } = useI18n();
  return (
    <View style={styles.chips} accessibilityRole="radiogroup">
      {LANGUAGES.map((l) => {
        const on = l.code === lang.code;
        return (
          <Pressable
            key={l.code}
            onPress={() => setLanguage(l.code)}
            accessibilityRole="radio"
            accessibilityState={{ selected: on }}
            style={[styles.chip, on && { backgroundColor: colors.ink }]}
          >
            <Text style={[styles.chipText, on && { color: colors.white }]}>{l.nativeName}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.paper, paddingHorizontal: size.gutter },
  title: { fontFamily: fonts.bold, fontSize: size.title, color: colors.ink, lineHeight: size.title * 1.25 },
  body: { fontFamily: fonts.regular, fontSize: size.body, color: colors.ink, lineHeight: size.body * 1.45 },
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    borderRadius: size.radius,
    paddingHorizontal: 20,
    paddingVertical: 14,
  },
  buttonText: { fontFamily: fonts.bold, fontSize: size.large },
  card: {
    flex: 1,
    aspectRatio: 1,
    borderRadius: 20,
    borderWidth: 2,
    borderColor: colors.ink,
    backgroundColor: colors.white,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    padding: 10,
  },
  cardText: { fontFamily: fonts.bold, fontSize: 21, color: colors.ink, textAlign: 'center' },
  chips: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  chip: {
    minHeight: 48,
    paddingHorizontal: 16,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: colors.ink,
    justifyContent: 'center',
  },
  chipText: { fontFamily: fonts.bold, fontSize: 19, color: colors.ink },
});
