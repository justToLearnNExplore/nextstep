import { useCallback, useEffect, useState } from 'react';
import { AppState, Linking, Share, StyleSheet, View } from 'react-native';

import { useI18n } from '../i18n';
import { get, type Invite, post, type Profile } from '../lib/api';
import { colors, size } from '../theme';
import { BigButton, Body } from './ui';

/**
 * No sign-up for anyone: the senior sends a one-time link on WhatsApp; whoever opens it can see
 * the family timeline until the senior taps Stop sharing.
 */
export function FamilySharing() {
  const { t } = useI18n();
  const [viewers, setViewers] = useState<string[] | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  const refresh = useCallback(() => {
    get<Profile>('/v1/me')
      .then((p) => setViewers(p.viewers))
      .catch(() => setViewers(null));
  }, []);

  useEffect(() => {
    refresh();
    // Family may join while the senior is in WhatsApp; update when they come back.
    const sub = AppState.addEventListener('change', (s) => s === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  const share = async () => {
    setStatus(null);
    try {
      const inv = await post<Invite>('/v1/family/invite', {});
      const message = t('shareMessage').replace('{url}', inv.join_url);
      const wa = `whatsapp://send?text=${encodeURIComponent(message)}`;
      if (await Linking.canOpenURL(wa)) await Linking.openURL(wa);
      else await Share.share({ message });
    } catch {
      setStatus(t('networkError'));
    }
  };

  const stop = async () => {
    try {
      await post('/v1/family/revoke', {});
      setStatus(t('sharingStopped'));
      refresh();
    } catch {
      setStatus(t('networkError'));
    }
  };

  return (
    <View style={styles.box}>
      <Body style={styles.title}>{t('familyTitle')}</Body>
      <View style={styles.note}>
        <Body>{t('shareWhy')}</Body>
      </View>
      <BigButton label={t('shareWithFamily')} icon="whatsapp" tone="go" tall onPress={share} />
      {viewers && viewers.length > 0 ? (
        <>
          <Body>{t('sharedWith').replace('{names}', viewers.join(', '))}</Body>
          <BigButton label={t('stopSharing')} icon="account-cancel" tone="outline" onPress={stop} />
        </>
      ) : viewers ? (
        <Body>{t('notShared')}</Body>
      ) : null}
      {status ? <Body>{status}</Body> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: 12, padding: 16, borderRadius: size.radius, borderWidth: 2, borderColor: colors.ink },
  title: { fontWeight: 'bold' },
  note: { backgroundColor: colors.amber, borderRadius: 12, padding: 12 },
});
