import { router } from 'expo-router';
import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { BigButton, Body, Screen, TaskCard, Title } from '../components/ui';
import { type StringKey, useI18n } from '../i18n';
import { heardErrorKey, NextStepAgent, startGoal } from '../lib/agent';
import { colors, fonts, size } from '../theme';

type Icon = 'cup' | 'bread-slice' | 'egg' | 'rice' | 'carrot' | 'food-apple';
const ITEMS: { label: StringKey; icon: Icon }[] = [
  { label: 'itemMilk', icon: 'cup' },
  { label: 'itemBread', icon: 'bread-slice' },
  { label: 'itemEggs', icon: 'egg' },
  { label: 'itemRice', icon: 'rice' },
  { label: 'itemVegetables', icon: 'carrot' },
  { label: 'itemFruit', icon: 'food-apple' },
];

/** "What would you like to order?": by voice, a picture, or typing. Then the Blinkit task. */
export default function Grocery() {
  const { t, lang } = useI18n();
  const [listening, setListening] = useState(false);
  const [typing, setTyping] = useState(false);
  const [text, setText] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    Speech.speak(t('whatToOrder'), { language: lang.tag, rate: 0.9 });
    return () => {
      Speech.stop();
    };
  }, [t, lang.tag]);

  const order = async (what: string) => {
    const item = what.trim();
    if (!item) return;
    Speech.stop();
    if (!(await startGoal(t('goalOrderItem').replace('{item}', item)))) setStatus(t('serviceOff'));
  };

  const speak = async () => {
    if (listening) return NextStepAgent.cancelListening();
    Speech.stop();
    setListening(true);
    setStatus(t('listening'));
    const heard = await NextStepAgent.listenOnce(lang.tag).finally(() => setListening(false));
    if (!heard.text) {
      if (heard.error !== 'cancelled') setStatus(t(heardErrorKey(heard.error)));
      return;
    }
    setStatus(`${t('youSaid')}: “${heard.text}”`);
    order(heard.text);
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Title>{t('whatToOrder')}</Title>
        <Body>{t('whatToOrderHint')}</Body>
        <BigButton
          label={listening ? t('listening') : t('speak')}
          icon={listening ? 'microphone-off' : 'microphone'}
          tone={listening ? 'go' : 'primary'}
          tall
          onPress={speak}
        />
        {status ? <Body style={{ textAlign: 'center' }}>{status}</Body> : null}

        <View style={styles.grid}>
          {[0, 2, 4].map((i) => (
            <View key={i} style={styles.row}>
              {ITEMS.slice(i, i + 2).map((it) => (
                <TaskCard key={it.label} label={t(it.label)} icon={it.icon} onPress={() => order(t(it.label))} />
              ))}
            </View>
          ))}
        </View>

        {typing ? (
          <>
            <TextInput
              value={text}
              onChangeText={setText}
              placeholder={t('typeItemsPlaceholder')}
              accessibilityLabel={t('whatToOrder')}
              autoFocus
              style={styles.input}
              placeholderTextColor="#5F6B76"
              onSubmitEditing={() => order(text)}
            />
            <BigButton label={t('orderThis')} icon="basket" tone="go" onPress={() => order(text)} />
          </>
        ) : (
          <BigButton label={t('typeInstead')} icon="keyboard" tone="outline" onPress={() => setTyping(true)} />
        )}
        <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 24, gap: 16 },
  grid: { gap: 12 },
  row: { flexDirection: 'row', gap: 12 },
  input: {
    minHeight: 72,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: colors.ink,
    backgroundColor: colors.white,
    paddingHorizontal: 16,
    fontFamily: fonts.regular,
    fontSize: size.large,
    color: colors.ink,
  },
});
