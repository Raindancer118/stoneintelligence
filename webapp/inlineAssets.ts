/** Fuer `build.assetsInlineLimit`: Schriften nie als data:-URL (CSP: font-src faellt auf 'self'), sonst Vites Standard. */
export function inlineAsset(file: string): boolean | undefined {
  return /\.(woff2?|ttf|otf)$/i.test(file) ? false : undefined;
}
