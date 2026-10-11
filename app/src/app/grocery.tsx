import { router } from 'expo-router';
import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';

import { MicButton } from '../components/MicButton';
import { BigButton, Body, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { startGoal } from '../lib/agent';
import { getLastOrder, saveLastOrder } from '../lib/grocery';

/** "What would you like to order?": by voice, repeat the last order, or a photo of a written list. */
export default function Grocery() {
  const { t, lang } = useI18n();
  const [last, setLast] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    getLastOrder().then(setLast);
    Speech.speak(t('whatToOrder'), { language: lang.tag, rate: 0.9 });
    return () => {
      Speech.stop();
    };
  }, [t, lang.tag]);

  const order = async (items: string) => {
    Speech.stop();
    await saveLastOrder(items);
    if (!(await startGoal(t('goalOrderItem').replace('{item}', items)))) setStatus(t('serviceOff'));
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Title>{t('whatToOrder')}</Title>
        <MicButton onHeard={order} size={150} />
        {last ? (
          <BigButton label={`${t('repeatLastOrder')}: ${last}`} icon="repeat" tone="outline" onPress={() => order(last)} />
        ) : null}
        <BigButton label={t('photoOfList')} icon="camera" tone="outline" onPress={() => router.push('/grocery-photo')} />
        {status ? <Body>{status}</Body> : null}
        <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 24, gap: 18 },
});
