import * as SecureStore from 'expo-secure-store';

const KEY = 'nextstep.lastGrocery';

/** The last grocery order's items, for "Repeat last order" (kept on the phone only). */
export async function getLastOrder(): Promise<string | null> {
  return SecureStore.getItemAsync(KEY).catch(() => null);
}

export async function saveLastOrder(items: string) {
  await SecureStore.setItemAsync(KEY, items.trim()).catch(() => {});
}
