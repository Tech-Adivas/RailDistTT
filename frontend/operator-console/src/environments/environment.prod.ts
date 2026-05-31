export const environment = {
  production: true,
  // TODO(config): Replace PLACEHOLDER_API_GATEWAY_URL with the production gateway URL.
  // Obtain from: Terraform output `api_gateway_url` or ALB DNS name.
  apiBaseUrl: '${PLACEHOLDER_API_GATEWAY_URL}',
  // TODO(config): Replace PLACEHOLDER_WS_URL with the WebSocket endpoint.
  wsUrl: '${PLACEHOLDER_WS_URL}',
  // TODO(config): Replace with your OIDC provider's issuer URL.
  // Obtain from: AWS Cognito user pool URL, Keycloak realm URL, etc.
  oidcIssuerUrl: '${PLACEHOLDER_OIDC_ISSUER_URL}',
  // TODO(config): Replace with the OIDC client ID registered for the operator console.
  oidcClientId: '${PLACEHOLDER_OIDC_CLIENT_ID}',
};
