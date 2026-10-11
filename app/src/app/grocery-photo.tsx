import { router } from 'expo-router';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as Speech from 'expo-speech';
import { useEffect, useRef, useState } from 'react';
import { Image, PermissionsAndroid, ScrollView, StyleSheet, Text, View } from 'react-native';

import { type GuidanceState, MedicineCamera, type MedicineCameraHandle } from '../components/MedicineCamera';
import { BigButton, Body, Screen, Title } from '../components/ui';
import { type StringKey, useI18n } from '../i18n';
import { startGoal } from '../lib/agent';
import { post } from '../lib/api';
import { saveLastOrder } from '../lib/grocery';
import { colors, fonts, size } from '../theme';

type GroceryList = { readable: boolean; items: { name: string; quantity: string }[]; say_to_user: string };
type Phase = 'camera' | 'reading' | 'review';

/** Photo of a handwritten grocery list → Gemini reads the items → "Order these?" */
export default function GroceryPhoto() {
  const { t, lang } = useI18n();
  const camera = useRef<MedicineCameraHandle>(null);
  const [allowed, setAllowed] = useState<boolean | null>(null);
  const [phase, setPhase] = useState<Phase>('camera');
  const [guidance, setGuidance] = useState<GuidanceState>('find_label');
  const [uri, setUri] = useState<string | null>(null);
  const [list, setList] = useState<GroceryList | null>(null);
  const [error, setError] = useState<string | null>(null);

  const say = (text: string) => {
    Speech.stop();
    Speech.speak(text, { language: lang.tag, rate: 0.9 });
  };

  useEffect(() => {
    PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA).then((r) => setAllowed(r === PermissionsAndroid.RESULTS.GRANTED));
    Speech.speak(t('pointAtList'), { language: lang.tag, rate: 0.9 });
    return () => {
      Speech.stop();
    };
  }, [t, lang.tag]);

  const take = async () => {
    if (!camera.current) return;
    try {
      const shot = await camera.current.takePhoto();
      setUri(shot.uri);
      setPhase('reading');
      say(t('readingList'));
      const ref = await ImageManipulator.manipulate(shot.uri).resize({ width: 1280 }).renderAsync();
      const small = await ref.saveAsync({ compress: 0.8, format: SaveFormat.JPEG, base64: true });
      const res = await post<GroceryList>('/v1/grocery/list', { language: lang.tag, image_b64: small.base64 });
      setList(res);
      setPhase('review');
      say(res.say_to_user);
    } catch {
      setError(t('networkError'));
      setPhase('review');
    }
  };

  const items = (list?.items ?? []).map((i) => (i.quantity ? `${i.quantity} ${i.name}` : i.name)).join(', ');

  const orderAll = async () => {
    Speech.stop();
    await saveLastOrder(items);
    await startGoal(t('goalOrderItem').replace('{item}', items));
  };

  if (allowed === false) {
    return (
      <Screen>
        <Body>{t('cameraPermission')}</Body>
        <BigButton label={t('back')} icon="arrow-left" onPress={() => router.back()} />
      </Screen>
    );
  }

  if (phase === 'camera') {
    return (
      <View style={styles.full}>
        {allowed ? (
          <MedicineCamera ref={camera} style={StyleSheet.absoluteFill} onGuidance={(e) => setGuidance(e.nativeEvent.state)} />
        ) : null}
        <View style={styles.banner}>
          <Text style={styles.bannerText}>{guidance === 'find_label' ? t('pointAtList') : t(`guide_${guidance}` as StringKey)}</Text>
        </View>
        <View style={styles.controls}>
          <BigButton label={t('takePhoto')} icon="camera" tone={guidance === 'ready' ? 'go' : 'primary'} tall onPress={take} />
          <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
        </View>
      </View>
    );
  }

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.review}>
        <Title>{t('photoOfList')}</Title>
        {uri ? <Image source={{ uri }} style={styles.photo} resizeMode="contain" /> : null}
        {phase === 'reading' ? <Body>{t('readingList')}</Body> : null}
        {error ? <Body>{error}</Body> : null}
        {list ? <Body>{list.say_to_user}</Body> : null}
        {list?.readable && list.items.length ? (
          <BigButton label={t('orderThese')} icon="basket" tone="go" tall onPress={orderAll} />
        ) : null}
        {phase === 'review' ? (
          <BigButton label={t('retake')} icon="camera-retake" tone="outline" onPress={() => { setList(null); setError(null); setPhase('camera'); }} />
        ) : null}
        <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  full: { flex: 1, backgroundColor: colors.ink },
  banner: { margin: size.gutter, marginTop: 48, borderRadius: size.radius, padding: 18, backgroundColor: colors.amber },
  bannerText: { fontFamily: fonts.bold, fontSize: 24, lineHeight: 32, textAlign: 'center', color: colors.ink },
  controls: { marginTop: 'auto', padding: size.gutter, gap: 12, backgroundColor: colors.paper, borderTopLeftRadius: 24, borderTopRightRadius: 24 },
  review: { paddingVertical: 24, gap: 16 },
  photo: { width: '100%', height: 260, borderRadius: size.radius, backgroundColor: colors.mist },
});
