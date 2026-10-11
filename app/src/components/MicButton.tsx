import { MaterialCommunityIcons } from '@expo/vector-icons';
import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { useI18n } from '../i18n';
import { heardErrorKey, NextStepAgent } from '../lib/agent';
import { colors, fonts } from '../theme';
import { Body } from './ui';

const BARS = 12;
type Phase = 'idle' | 'listening' | 'captured';

/**
 * The big square "tap and speak" button used across NextStep. Stops any speech first (so the mic
 * never hears NextStep's own voice), then listens in the user's language. While listening it
 * shows bars that move with the voice (the phone also beeps when the mic opens and when it has
 * heard the user), then "Got it, one moment…" while the words are transcribed.
 */
export function MicButton({
  onHeard,
  label,
  size = 190,
}: {
  onHeard: (text: string) => void;
  label?: string;
  /** Height of the button; it always spans the full width. */
  size?: number;
}) {
  const { t, lang } = useI18n();
  const [phase, setPhase] = useState<Phase>('idle');
  const [levels, setLevels] = useState<number[]>(() => Array(BARS).fill(0));
  const [status, setStatus] = useState<string | null>(null);
  const active = phase !== 'idle';

  useEffect(() => {
    if (!active) return;
    const sub = NextStepAgent.addListener('onAgentEvent', (e) => {
      if (e.type !== 'voice') return;
      try {
        const p = JSON.parse(e.payload) as { state: string; level: number };
        if (p.state === 'level') setLevels((l) => [...l.slice(1), Math.sqrt(p.level)]);
        else if (p.state === 'captured') setPhase('captured');
      } catch {
        // malformed event: ignore
      }
    });
    return () => sub.remove();
  }, [active]);

  const press = async () => {
    if (active) {
      NextStepAgent.cancelListening();
      return;
    }
    Speech.stop();
    await new Promise((r) => setTimeout(r, 350)); // let the speaker fall silent before recording
    setLevels(Array(BARS).fill(0));
    setPhase('listening');
    setStatus(null);
    const heard = await NextStepAgent.listenOnce(lang.tag).finally(() => setPhase('idle'));
    if (!heard.text) {
      setStatus(heard.error === 'cancelled' ? null : t(heardErrorKey(heard.error)));
      return;
    }
    setStatus(`${t('youSaid')}: “${heard.text}”`);
    onHeard(heard.text);
  };

  const caption = phase === 'listening' ? t('listeningNow') : phase === 'captured' ? t('gotItWait') : (label ?? t('speak'));

  return (
    <View style={styles.wrap}>
      <Pressable
        onPress={press}
        accessibilityRole="button"
        accessibilityLabel={label ?? t('speak')}
        accessibilityState={{ busy: active }}
        style={({ pressed }) => [
          styles.mic,
          { height: size, backgroundColor: active ? colors.go : colors.ink },
          pressed && { transform: [{ scale: 0.98 }] },
        ]}
      >
        {phase === 'listening' ? (
          <View style={[styles.bars, { height: size * 0.32 }]}>
            {levels.map((l, i) => (
              <View key={i} style={[styles.bar, { height: 8 + l * (size * 0.3) }]} />
            ))}
          </View>
        ) : (
          <MaterialCommunityIcons
            name={phase === 'captured' ? 'check-circle-outline' : 'microphone'}
            size={Math.round(size * 0.3)}
            color={colors.white}
          />
        )}
        <Text style={styles.label}>{caption}</Text>
      </Pressable>
      {status ? <Body style={styles.status}>{status}</Body> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignSelf: 'stretch', gap: 12 },
  mic: { alignSelf: 'stretch', borderRadius: 24, alignItems: 'center', justifyContent: 'center', gap: 10 },
  bars: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  bar: { width: 8, borderRadius: 4, backgroundColor: colors.white },
  label: { fontFamily: fonts.bold, fontSize: 22, color: colors.white, textAlign: 'center', paddingHorizontal: 16 },
  status: { textAlign: 'center' },
});
