import * as SecureStore from 'expo-secure-store';
import { useCallback, useEffect, useState } from 'react';

/** The two people a senior shares with most: their doctor and one family member. */
export type Recipient = 'doctor' | 'family';
export type Contact = { name: string; phone: string };
export type Contacts = Partial<Record<Recipient, Contact>>;

const KEY = 'nextstep.contacts';

export function useContacts() {
  const [contacts, setContacts] = useState<Contacts>({});
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    SecureStore.getItemAsync(KEY)
      .then((raw) => raw && setContacts(JSON.parse(raw) as Contacts))
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, []);

  const save = useCallback((who: Recipient, c: Contact | undefined) => {
    setContacts((prev) => {
      const next = { ...prev, [who]: c };
      SecureStore.setItemAsync(KEY, JSON.stringify(next)).catch(() => {});
      return next;
    });
  }, []);

  return { contacts, loaded, save };
}
