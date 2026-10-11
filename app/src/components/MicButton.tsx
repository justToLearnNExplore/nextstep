import { MaterialCommunityIcons } from '@expo/vector-icons';
import * as Speech from 'expo-speech';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { useI18n } from '../i18n';
import { heardErrorKey, NextStepAgent } from '../lib/agent';
import { colors, fonts } from '../theme';
import { Body } from './ui';

/**
 * The big round "tap and speak" button used across NextStep. Stops any speech first (so the mic
 * never hears NextStep's own voice), listens in the user's language, and reports what was heard.
 */
export function MicButton({
  onHeard,
  label,
  size = 168,
}: {
  onHeard: (text: string) => void;
  label?: string;
  size?: number;
}) {
  const { t, lang } = useI18n();
  const [listening, setListening] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  const press = async () => {
    if (listening) {
      NextStepAgent.cancelListening();
      return;
    }
    Speech.stop();
    await new Promise((r) => setTimeout(r, 350)); // let the speaker fall silent before recording
    setListening(true);
    setStatus(t('listening'));
    const heard = await NextStepAgent.listenOnce(lang.tag).finally(() => setListening(false));
    if (!heard.text) {
      setStatus(heard.error === 'cancelled' ? null : t(heardErrorKey(heard.error)));
      return;
    }
    setStatus(`${t('youSaid')}: “${heard.text}”`);
    onHeard(heard.text);
  };

  return (
    <View style={styles.wrap}>
      <Pressable
        onPress={press}
        accessibilityRole="button"
        accessibilityLabel={label ?? t('speak')}
        style={({ pressed }) => [
          styles.mic,
          { width: size, height: size, borderRadius: size / 2, backgroundColor: listening ? colors.go : colors.ink },
          pressed && { transform: [{ scale: 0.97 }] },
        ]}
      >
        <MaterialCommunityIcons name={listening ? 'microphone-off' : 'microphone'} size={size * 0.36} color={colors.white} />
        <Text style={styles.label}>{listening ? t('listening') : (label ?? t('speak'))}</Text>
      </Pressable>
      {status ? <Body style={styles.status}>{status}</Body> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: 'center', gap: 12 },
  mic: { alignItems: 'center', justifyContent: 'center', gap: 6 },
  label: { fontFamily: fonts.bold, fontSize: 18, color: colors.white, textAlign: 'center', paddingHorizontal: 12 },
  status: { textAlign: 'center' },
});
