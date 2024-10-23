import { OAuthRelayFlow } from 'components/Screens/OauthRelay'

export function getOAuthRelayAuthToken(): string {
  return localStorage.getItem('oauth-relay-auth-token') ?? ''
}

export function getOauthRelayAuthFlow(): OAuthRelayFlow | undefined {
  const flow = localStorage.getItem('oauth-relay-auth-flow') ?? undefined
  if (flow !== undefined) {
    return flow as OAuthRelayFlow
  } else {
    return flow
  }
}

export function storeOauthRelayAuthTokenAndFlow(
  authToken: string,
  flow: OAuthRelayFlow
) {
  localStorage.setItem('oauth-relay-auth-token', authToken)
  localStorage.setItem('oauth-relay-auth-flow', flow)
}

export function clearOAuthRelayAuthTokenAndFlow() {
  localStorage.removeItem('oauth-relay-auth-token')
  localStorage.removeItem('oauth-relay-auth-flow')
}
