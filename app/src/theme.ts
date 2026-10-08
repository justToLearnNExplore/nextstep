/**
 * NextStep palette, chosen for ageing eyes: saturated, high-contrast (all text pairs pass WCAG AAA
 * 7:1), warm off-white instead of pure white to cut glare, and no reliance on blue/green/violet
 * distinctions (the ageing lens filters those). Colour is never the only signal: every state also
 * has a word and an icon.
 */
export const colors = {
  ink: '#12263A',
  paper: '#FFFDF8',
  saffron: '#F2A33A',
  go: '#155E39',
  stop: '#A11D15',
  amber: '#FFF1C9',
  mist: '#E3E6EA',
  white: '#FFFFFF',
} as const;

export const fonts = {
  regular: 'AtkinsonHyperlegible_400Regular',
  bold: 'AtkinsonHyperlegible_700Bold',
} as const;

/** Minimum sizes. Body text never below 20, touch targets never below 64dp. */
export const size = {
  body: 20,
  large: 24,
  title: 30,
  touch: 64,
  radius: 16,
  gutter: 20,
} as const;
