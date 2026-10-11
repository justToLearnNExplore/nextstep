import { router } from 'expo-router';
import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { PermissionsAndroid, ScrollView, StyleSheet, View } from 'react-native';

import { BigButton, Body, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { NextStepAgent } from '../lib/agent';
import { post } from '../lib/api';
import { colors, size } from '../theme';

type Ask = { message: string; onYes: () => void } | null;

/**
 * Phone help: sound problems (fixed only after a yes) and "what did I miss in the last hour"
 * (missed calls + messages, summarised by Gemini and read aloud; nothing is stored).
 */
export default function Phone() {
  const { t, lang } = useI18n();
  const [message, setMessage] = useState<string | null>(null);
  const [ask, setAsk] = useState<Ask>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => () => {
    Speech.stop();
  }, []);

  const say = (text: string) => {
    setMessage(text);
    Speech.stop();
    Speech.speak(text, { language: lang.tag, rate: 0.9 });
  };

  const fix = (kind: 'media' | 'ringer') => {
    const res = NextStepAgent.fixSound(kind);
    setAsk(null);
    if (res === 'ok') say(t('soundFixed'));
    else {
      say(t('needsDndAccess'));
      NextStepAgent.openDoNotDisturbAccess();
    }
  };

  const noSound = () => {
    const s = NextStepAgent.getAudioState();
    if (s.mediaVolume <= s.mediaMax * 0.25) {
      const q = t('soundLowAsk');
      say(q);
      setAsk({ message: q, onYes: () => fix('media') });
    } else if (s.ringerMode !== 'normal' || s.doNotDisturb) {
      silent();
    } else {
      setAsk(null);
      say(t('soundLooksFine'));
    }
  };

  const silent = () => {
    const s = NextStepAgent.getAudioState();
    if (s.ringerMode !== 'normal' || s.doNotDisturb) {
      const q = s.doNotDisturb ? t('dndOnAsk') : t('silentAsk');
      say(q);
      setAsk({ message: q, onYes: () => fix('ringer') });
    } else {
      setAsk(null);
      say(t('notSilent'));
    }
  };

  const missed = async () => {
    setAsk(null);
    setBusy(true);
    say(t('checkingMissed'));
    try {
      await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.READ_CALL_LOG,
        PermissionsAndroid.PERMISSIONS.READ_SMS,
        PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
      ]);
      const activity = await NextStepAgent.getRecentActivity(Date.now() - 60 * 60 * 1000);
      const res = await post<{ summary: string }>('/v1/phone/missed', {
        language: lang.tag,
        calls: activity.calls,
        messages: activity.messages,
      });
      say(res.summary);
    } catch {
      say(t('networkError'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Title>{t('phoneHelpTitle')}</Title>
        <BigButton label={t('noSound')} icon="volume-off" tone="outline" tall onPress={noSound} />
        <BigButton label={t('phoneSilent')} icon="bell-off" tone="outline" tall onPress={silent} />
        <BigButton label={t('whatDidIMiss')} icon="clock-outline" tone="outline" tall onPress={missed} />
        {message ? (
          <View style={styles.answer}>
            <Body>{busy ? t('checkingMissed') : message}</Body>
          </View>
        ) : null}
        {ask ? (
          <View style={styles.row}>
            <View style={{ flex: 1 }}>
              <BigButton label={t('yes')} icon="check" tone="go" onPress={ask.onYes} />
            </View>
            <View style={{ flex: 1 }}>
              <BigButton label={t('no')} tone="outline" onPress={() => { setAsk(null); Speech.stop(); }} />
            </View>
          </View>
        ) : null}
        <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 24, gap: 16 },
  answer: { backgroundColor: colors.amber, borderRadius: size.radius, padding: 16 },
  row: { flexDirection: 'row', gap: 12 },
});
