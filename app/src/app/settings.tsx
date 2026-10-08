import { router } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, Switch, View } from 'react-native';

import { BigButton, Body, LanguageChips, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { configureAgent, NextStepAgent } from '../lib/agent';
import { colors } from '../theme';

export default function Settings() {
  const { t } = useI18n();
  const [scam, setScam] = useState(NextStepAgent.isNotificationAccessEnabled());
  const [bubble, setBubble] = useState(true);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.body}>
        <Title>{t('settings')}</Title>

        <Body style={styles.label}>{t('language')}</Body>
        <LanguageChips />

        <Row
          label={t('showBubble')}
          value={bubble}
          onChange={(v) => {
            setBubble(v);
            if (v) NextStepAgent.showBubble();
            else NextStepAgent.hideBubble();
          }}
        />
        <Row
          label={t('scamCheck')}
          value={scam}
          onChange={(v) => {
            setScam(v);
            configureAgent({ scamCheckEnabled: v });
            if (v && !NextStepAgent.isNotificationAccessEnabled()) NextStepAgent.openNotificationAccessSettings();
          }}
        />

        <BigButton label={t('actionLog')} icon="format-list-checks" tone="outline" onPress={() => router.push('/log')} />
        <BigButton label={t('back')} icon="arrow-left" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

function Row({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={styles.row}>
      <Body style={{ flex: 1 }}>{label}</Body>
      <Switch
        value={value}
        onValueChange={onChange}
        accessibilityLabel={label}
        trackColor={{ true: colors.go, false: colors.mist }}
        thumbColor={colors.white}
        style={{ transform: [{ scale: 1.4 }] }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  body: { paddingVertical: 24, gap: 20 },
  label: { fontWeight: 'bold' },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 64, gap: 16 },
});
