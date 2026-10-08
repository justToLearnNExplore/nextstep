import { Redirect } from 'expo-router';
import * as SecureStore from 'expo-secure-store';
import { useEffect, useState } from 'react';

export const ONBOARDED_KEY = 'nextstep.onboarded';

export default function Index() {
  const [onboarded, setOnboarded] = useState<boolean | null>(null);
  useEffect(() => {
    SecureStore.getItemAsync(ONBOARDED_KEY).then((v) => setOnboarded(v === '1'));
  }, []);
  if (onboarded === null) return null;
  return <Redirect href={onboarded ? '/talk' : '/onboarding'} />;
}
