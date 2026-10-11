import * as SecureStore from 'expo-secure-store';

const KEY = 'nextstep.homeAddress';

/** The senior's home address for "Take me home" rides (kept on the phone only). */
export async function getHomeAddress(): Promise<string | null> {
  return SecureStore.getItemAsync(KEY).catch(() => null);
}

export async function saveHomeAddress(address: string) {
  const a = address.trim();
  if (a) await SecureStore.setItemAsync(KEY, a).catch(() => {});
  else await SecureStore.deleteItemAsync(KEY).catch(() => {});
}
