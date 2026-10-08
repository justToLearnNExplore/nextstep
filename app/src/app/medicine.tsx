import { router, useLocalSearchParams } from 'expo-router';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as Speech from 'expo-speech';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, PermissionsAndroid, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  type CapturedPhoto,
  type GuidanceState,
  MedicineCamera,
  type MedicineCameraHandle,
} from '../components/MedicineCamera';
import { BigButton, Body, Screen, Title } from '../components/ui';
import { type StringKey, useI18n } from '../i18n';
import { NextStepAgent } from '../lib/agent';
import { type MedicineInfo, post } from '../lib/api';
import { type Recipient, useContacts } from '../lib/contacts';
import { colors, fonts, size } from '../theme';

type Phase = 'permission' | 'camera' | 'reading' | 'review';

const SPEAK_GAP_MS = 2500;

/**
 * Medicine photo → doctor/family on WhatsApp.
 * 1. Guided camera: spoken framing hints until the label is readable (on-device ML Kit).
 * 2. User taps Take photo. Gemini reads the label and drafts a caption.
 * 3. User picks who to send to; NextStep opens that WhatsApp chat with the photo and asks
 *    "Send?" before tapping Send (handled by the native agent overlay).
 */
export default function Medicine() {
  const { t, lang } = useI18n();
  const { to } = useLocalSearchParams<{ to?: Recipient }>();
  const { contacts } = useContacts();
  const camera = useRef<MedicineCameraHandle>(null);

  const [phase, setPhase] = useState<Phase>('permission');
  const [guidance, setGuidance] = useState<GuidanceState>('find_label');
  const [torch, setTorch] = useState(false);
  const [photo, setPhoto] = useState<CapturedPhoto | null>(null);
  const [info, setInfo] = useState<MedicineInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lastSpoken = useRef({ key: '', at: 0 });

  const say = useCallback(
    (text: string) => {
      Speech.stop();
      Speech.speak(text, { language: lang.tag, rate: 0.9 });
    },
    [lang.tag],
  );

  useEffect(() => {
    PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA).then((r) => {
      if (r === PermissionsAndroid.RESULTS.GRANTED) setPhase('camera');
      else setError(t('cameraPermission'));
    });
    return () => {
      Speech.stop();
    };
  }, [t]);

  const onGuidance = (state: GuidanceState) => {
    setGuidance(state);
    const now = Date.now();
    if (state !== lastSpoken.current.key && now - lastSpoken.current.at > SPEAK_GAP_MS) {
      lastSpoken.current = { key: state, at: now };
      say(t(`guide_${state}` as StringKey));
    }
  };

  const takePhoto = async () => {
    if (!camera.current) return;
    Speech.stop();
    let shot: CapturedPhoto;
    try {
      shot = await camera.current.takePhoto();
    } catch {
      say(t('cameraError'));
      return;
    }
    setPhoto(shot);
    setPhase('reading');
    say(t('reading'));
    try {
      setInfo(await readLabel(shot, lang.tag, to ?? 'doctor'));
    } catch {
      // Backend unreachable: still let the user send, with a plain caption.
      setInfo(null);
    }
    setPhase('review');
  };

  useEffect(() => {
    if (phase === 'review' && info?.say_to_user) say(info.say_to_user);
  }, [phase, info, say]);

  const retake = () => {
    setPhoto(null);
    setInfo(null);
    setPhase('camera');
  };

  const send = (who: Recipient) => {
    const c = contacts[who];
    if (!photo || !c) return;
    NextStepAgent.shareImageToWhatsApp(photo.path, c.phone, info?.caption || t('captionFallback'), c.name);
  };

  if (error) {
    return (
      <Screen style={styles.center}>
        <Body>{error}</Body>
        <BigButton label={t('back')} icon="arrow-left" onPress={() => router.back()} />
      </Screen>
    );
  }

  if (phase === 'camera' || phase === 'permission') {
    const ready = guidance === 'ready';
    return (
      <View style={styles.full}>
        {phase === 'camera' ? (
          <MedicineCamera
            ref={camera}
            style={StyleSheet.absoluteFill}
            onGuidance={(e) => onGuidance(e.nativeEvent.state)}
            onCameraError={() => setError(t('cameraError'))}
          />
        ) : null}
        <View style={[styles.banner, { backgroundColor: ready ? colors.go : colors.amber }]} accessibilityLiveRegion="polite">
          <Text style={[styles.bannerText, { color: ready ? colors.white : colors.ink }]}>
            {t(`guide_${guidance}` as StringKey)}
          </Text>
        </View>
        <View style={styles.controls}>
          <BigButton
            label={t('takePhoto')}
            icon="camera"
            tone={ready ? 'go' : 'primary'}
            tall
            onPress={takePhoto}
          />
          <View style={styles.row}>
            <View style={{ flex: 1 }}>
              <BigButton
                label={torch ? t('torchOff') : t('torchOn')}
                icon={torch ? 'flashlight-off' : 'flashlight'}
                tone="outline"
                onPress={() => {
                  camera.current?.setTorch(!torch);
                  setTorch(!torch);
                }}
              />
            </View>
            <View style={{ flex: 1 }}>
              <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
            </View>
          </View>
        </View>
      </View>
    );
  }

  const order: Recipient[] = to === 'family' ? ['family', 'doctor'] : ['doctor', 'family'];
  const available = order.filter((w) => contacts[w]);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.review}>
        <Title>{t('medicineTitle')}</Title>
        {photo ? <Image source={{ uri: photo.uri }} style={styles.photo} resizeMode="contain" /> : null}
        {phase === 'reading' ? (
          <Body>{t('reading')}</Body>
        ) : (
          <>
            {info?.say_to_user ? (
              <View style={[styles.note, info.expired || !info.readable ? { backgroundColor: colors.amber } : null]}>
                <Body>{info.say_to_user}</Body>
              </View>
            ) : null}
            {info && !info.readable ? (
              <BigButton label={t('retake')} icon="camera-retake" onPress={retake} />
            ) : null}
            {available.map((who) => (
              <BigButton
                key={who}
                label={t('sendTo').replace('{name}', contacts[who]!.name)}
                icon="whatsapp"
                tone="go"
                onPress={() => send(who)}
              />
            ))}
            {available.length === 0 ? (
              <>
                <Body>{t('noContacts')}</Body>
                <BigButton label={t('openSettings')} icon="cog" onPress={() => router.push('/settings')} />
              </>
            ) : null}
            {info?.readable !== false ? (
              <BigButton label={t('retake')} icon="camera-retake" tone="outline" onPress={retake} />
            ) : null}
            <BigButton label={t('back')} icon="arrow-left" tone="outline" onPress={() => router.back()} />
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

/** Downscale (faster upload), then let Gemini read the label and draft the caption. */
async function readLabel(shot: CapturedPhoto, language: string, recipient: Recipient): Promise<MedicineInfo> {
  const ref = await ImageManipulator.manipulate(shot.uri).resize({ width: 1280 }).renderAsync();
  const small = await ref.saveAsync({ compress: 0.8, format: SaveFormat.JPEG, base64: true });
  return post<MedicineInfo>('/v1/medicine/read', {
    language,
    recipient,
    image_b64: small.base64,
    ocr_text: shot.text,
  });
}

const styles = StyleSheet.create({
  full: { flex: 1, backgroundColor: colors.ink },
  center: { justifyContent: 'center', gap: 20 },
  banner: { margin: size.gutter, marginTop: 48, borderRadius: size.radius, padding: 18 },
  bannerText: { fontFamily: fonts.bold, fontSize: 24, lineHeight: 32, textAlign: 'center' },
  controls: { marginTop: 'auto', padding: size.gutter, gap: 12, backgroundColor: colors.paper, borderTopLeftRadius: 24, borderTopRightRadius: 24 },
  row: { flexDirection: 'row', gap: 12 },
  review: { paddingVertical: 24, gap: 16 },
  photo: { width: '100%', height: 280, borderRadius: size.radius, backgroundColor: colors.mist },
  note: { backgroundColor: colors.mist, borderRadius: size.radius, padding: 16 },
});
