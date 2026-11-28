export const REQUIRED_BUILD_ENV = [
  "VITE_PLATFORM_API_URL",
  "VITE_PLATFORM_WS_URL",
  "VITE_OIDC_ISSUER_URL",
  "VITE_OIDC_CLIENT_ID",
] as const;

export function missingBuildEnv(env: Record<string, string | undefined>): string[] {
  return REQUIRED_BUILD_ENV.filter((key) => !env[key]?.trim());
}
