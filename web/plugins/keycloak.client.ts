import Keycloak from 'keycloak-js'

// Authorization Code + PKCE login for the SPA (client-only plugin —
// keycloak-js needs the browser). `login-required` redirects any
// unauthenticated visitor to the Keycloak login page (demo/demo) and
// returns here with an authorization code; keycloak-js exchanges it
// for tokens and keeps them in memory (never localStorage).
export default defineNuxtPlugin(async () => {
  const keycloak = new Keycloak({
    url: 'http://localhost:8180',
    realm: 'cargo',
    clientId: 'cargo-web',
  })

  await keycloak.init({
    onLoad: 'login-required',
    pkceMethod: 'S256',
    checkLoginIframe: false,
  })

  return { provide: { keycloak } }
})
