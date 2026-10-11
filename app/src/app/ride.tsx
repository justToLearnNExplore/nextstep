import { router } from 'expo-router';
import * as Speech from 'expo-speech';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';

import { MicButton } from '../components/MicButton';
import { BigButton, Body, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { startGoal } from '../lib/agent';
import { getHomeAddress } from '../lib/places';

/** "Where would you like to go?" → Uber; the fare is read out and booking needs a yes. */
export default function Ride() {
  const { t, lang } = useI18n();
  const [home, setHome] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    getHomeAddress().then(setHome);
    Speech.speak(t('whereTo'), { language: lang.tag, rate: 0.9 });
    return () => {
      Speech.stop();
    };
  }, [t, lang.tag]);

  const book = async (destination: string) => {
    Speech.stop();
    if (!(await startGoal(t('goalRide').replace('{place}', destination)))) setStatus(t('serviceOff'));
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Title>{t('whereTo')}</Title>
        <MicButton onHeard={book} size={150} />
        {home ? <BigButton label={t('takeMeHome')} icon="home" tone="outline" onPress={() => book(home)} /> : null}
        {status ? <Body>{status}</Body> : null}
        <Body style={styles.hint}>{t('cancelRideHint')}</Body>
        <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 24, gap: 18 },
  hint: { textAlign: 'center' },
});
