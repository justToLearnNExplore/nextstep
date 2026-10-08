import { router } from 'expo-router';
import { FlatList, StyleSheet, Text, View } from 'react-native';

import { BigButton, Body, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { useAgentLog } from '../lib/agent';
import { colors, fonts } from '../theme';

/** Transparent record of what NextStep saw, planned and did, newest first. */
export default function Log() {
  const { t } = useI18n();
  const entries = useAgentLog();

  return (
    <Screen>
      <View style={{ paddingVertical: 20 }}>
        <Title>{t('actionLog')}</Title>
      </View>
      <FlatList
        data={entries}
        keyExtractor={(e, i) => `${e.at}-${i}`}
        ListEmptyComponent={<Body>{t('actionLogEmpty')}</Body>}
        ItemSeparatorComponent={() => <View style={styles.sep} />}
        renderItem={({ item }) => (
          <View style={styles.item}>
            <Text style={styles.kind}>
              {new Date(item.at).toLocaleTimeString()} · {item.kind}
            </Text>
            <Body>{describe(item.data)}</Body>
          </View>
        )}
      />
      <View style={{ paddingVertical: 16 }}>
        <BigButton label={t('back')} icon="arrow-left" onPress={() => router.back()} />
      </View>
    </Screen>
  );
}

function describe(d: Record<string, unknown>): string {
  const pick = (k: string) => (typeof d[k] === 'string' ? (d[k] as string) : undefined);
  const plan = d.plan as { summary?: string } | undefined;
  return (
    plan?.summary ??
    pick('intent') ??
    pick('message') ??
    pick('status_text') ??
    pick('explanation') ??
    pick('warning') ??
    JSON.stringify(d).slice(0, 160)
  );
}

const styles = StyleSheet.create({
  item: { paddingVertical: 12, gap: 4 },
  kind: { fontFamily: fonts.bold, fontSize: 16, color: colors.ink },
  sep: { height: 1, backgroundColor: colors.mist },
});
