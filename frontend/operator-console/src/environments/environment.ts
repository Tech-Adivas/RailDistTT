export const environment = {
  production: false,
  // TODO(config): Update API_BASE_URL if your local gateway runs on a different port.
  apiBaseUrl: '',   // empty = same origin, proxied via proxy.conf.json
  wsUrl: 'ws://localhost:8080/ws',
  // TODO(config): Replace with your OIDC provider's discovery URL for local dev.
  // DEV-ONLY: set to empty string to skip OIDC validation in local mode.
  oidcIssuerUrl: '',
  oidcClientId: 'operator-console-dev',   // DEV-ONLY
};
