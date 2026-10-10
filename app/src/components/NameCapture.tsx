import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';

import { useI18n } from '../i18n';
import { heardErrorKey, NextStepAgent } from '../lib/agent';
import { put } from '../lib/api';
import { createAccount, extractName } from '../lib/identity';
import { colors, fonts, size } from '../theme';
import { BigButton, Body, Title } from './ui';

type Phase = 'ask' | 'listening' | 'confirm' | 'typing' | 'saving';

/**
 * "What should I call you?" → speak → "Did I hear Kamala right?" → yes → account created.
 * No username, password or OTP: the name is the only thing the senior ever provides.
 */
export function NameCapture({ initial, onDone }: { initial?: string; onDone: (name: string) => void }) {
  const { t, lang } = useI18n();
  const [phase, setPhase] = useState<Phase>('ask');
  const [name, setName] = useState(initial ?? '');
  const [error, setError] = useState<string | null>(null);

  const say = (text: string) => {
    Speech.stop();
    Speech.speak(text, { language: lang.tag, rate: 0.9 });
  };

  useEffect(() => {
    Speech.speak(`${t('askName')} ${t('askNameHint')}`, { language: lang.tag, rate: 0.9 });
    return () => {
      Speech.stop();
    };
  }, [t, lang.tag]);

  const listen = async () => {
    Speech.stop();
    setError(null);
    setPhase('listening');
    const heard = await NextStepAgent.listenOnce(lang.tag).catch(() => ({ text: null, error: 'network' as const }));
    const n = heard.text ? extractName(heard.text, lang.strings.namePrefixes, lang.strings.nameSuffixes) : '';
    if (!n) {
      const msg = t(heardErrorKey(heard.error));
      setPhase('ask');
      setError(msg);
      say(msg);
      return;
    }
    setName(n);
    setPhase('confirm');
    say(`${n}. ${t('didIHear')}`);
  };

  const save = async () => {
    const n = name.trim();
    if (!n) return;
    setPhase('saving');
    try {
      await createAccount(n);
      await put('/v1/me', { display_name: n, language: lang.tag });
    } catch {
      // The account works offline with a device id; the server copy of the name is retried later.
    }
    say(t('greeting').replace('{name}', n));
    onDone(n);
  };

  return (
    <View style={styles.wrap}>
      <Title>{t('askName')}</Title>

      {phase === 'ask' || phase === 'listening' ? (
        <>
          <Body>{t('askNameHint')}</Body>
          {error ? <Body style={{ color: colors.stop }}>{error}</Body> : null}
          <BigButton
            label={phase === 'listening' ? t('listening') : t('sayName')}
            icon="microphone"
            tone={phase === 'listening' ? 'go' : 'primary'}
            tall
            onPress={listen}
          />
          <BigButton label={t('typeInstead')} icon="keyboard" tone="outline" onPress={() => setPhase('typing')} />
        </>
      ) : null}

      {phase === 'confirm' ? (
        <>
          <Body>{t('didIHear')}</Body>
          <Text style={styles.name} accessibilityRole="text">
            {name}
          </Text>
          <BigButton label={t('yesRight')} icon="check" tone="go" tall onPress={save} />
          <BigButton label={t('tryAgain')} icon="microphone" tone="outline" onPress={listen} />
          <BigButton label={t('typeInstead')} icon="keyboard" tone="outline" onPress={() => setPhase('typing')} />
        </>
      ) : null}

      {phase === 'typing' ? (
        <>
          <TextInput
            value={name}
            onChangeText={setName}
            placeholder={t('yourName')}
            accessibilityLabel={t('yourName')}
            autoFocus
            autoCapitalize="words"
            maxLength={40}
            style={styles.input}
            placeholderTextColor="#5F6B76"
          />
          <BigButton label={t('yesRight')} icon="check" tone="go" tall onPress={save} />
          <BigButton label={t('sayName')} icon="microphone" tone="outline" onPress={listen} />
        </>
      ) : null}

      {phase === 'saving' ? <Body>{t('settingUp')}</Body> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: 16 },
  name: {
    fontFamily: fonts.bold,
    fontSize: 40,
    color: colors.ink,
    textAlign: 'center',
    paddingVertical: 16,
    backgroundColor: colors.amber,
    borderRadius: size.radius,
  },
  input: {
    minHeight: 72,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: colors.ink,
    backgroundColor: colors.white,
    paddingHorizontal: 16,
    fontFamily: fonts.bold,
    fontSize: 28,
    color: colors.ink,
  },
});
