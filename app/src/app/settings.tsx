import { router } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';

import { BigButton, Body, LanguageChips, Screen, Title } from '../components/ui';
import { useI18n } from '../i18n';
import { configureAgent, NextStepAgent } from '../lib/agent';
import { type Contact, type Recipient, useContacts } from '../lib/contacts';
import { colors, fonts, size } from '../theme';

export default function Settings() {
  const { t } = useI18n();
  const [scam, setScam] = useState(NextStepAgent.isNotificationAccessEnabled());
  const [bubble, setBubble] = useState(true);
  const { contacts, loaded, save } = useContacts();

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

        {loaded ? (
          <>
            <ContactEditor title={t('myDoctor')} who="doctor" initial={contacts.doctor} onSave={save} />
            <ContactEditor title={t('myFamily')} who="family" initial={contacts.family} onSave={save} />
          </>
        ) : null}

        <BigButton label={t('actionLog')} icon="format-list-checks" tone="outline" onPress={() => router.push('/log')} />
        <BigButton label={t('back')} icon="arrow-left" onPress={() => router.back()} />
      </ScrollView>
    </Screen>
  );
}

/** Name + WhatsApp number for the doctor or a family member (used by the medicine photo flow). */
function ContactEditor({
  title,
  who,
  initial,
  onSave,
}: {
  title: string;
  who: Recipient;
  initial?: Contact;
  onSave: (who: Recipient, c: Contact | undefined) => void;
}) {
  const { t } = useI18n();
  const [name, setName] = useState(initial?.name ?? '');
  const [phone, setPhone] = useState(initial?.phone ?? '');
  const [saved, setSaved] = useState(false);
  const digits = phone.replace(/\D/g, '');
  return (
    <View style={styles.contact}>
      <Body style={styles.label}>{title}</Body>
      <TextInput
        value={name}
        onChangeText={(v) => (setName(v), setSaved(false))}
        placeholder={t('contactName')}
        accessibilityLabel={`${title} ${t('contactName')}`}
        style={styles.input}
        placeholderTextColor="#5F6B76"
      />
      <TextInput
        value={phone}
        onChangeText={(v) => (setPhone(v), setSaved(false))}
        placeholder={t('contactPhone')}
        accessibilityLabel={`${title} ${t('contactPhone')}`}
        keyboardType="phone-pad"
        style={styles.input}
        placeholderTextColor="#5F6B76"
      />
      <BigButton
        label={saved ? t('saved') : t('save')}
        icon={saved ? 'check' : 'content-save'}
        tone={saved ? 'go' : 'outline'}
        onPress={() => {
          onSave(who, name.trim() && digits.length >= 10 ? { name: name.trim(), phone: digits } : undefined);
          setSaved(true);
        }}
      />
    </View>
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
  contact: { gap: 10, padding: 16, borderRadius: size.radius, borderWidth: 2, borderColor: colors.ink },
  input: {
    minHeight: size.touch,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: colors.ink,
    backgroundColor: colors.white,
    paddingHorizontal: 14,
    fontFamily: fonts.regular,
    fontSize: size.body,
    color: colors.ink,
  },
});
