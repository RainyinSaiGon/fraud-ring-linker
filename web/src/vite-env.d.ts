/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The identity provider's issuer URL, for sign-in with OpenID Connect. */
  readonly VITE_OIDC_ISSUER?: string;
  /** This page's public client ID at the identity provider. There is no client secret. */
  readonly VITE_OIDC_CLIENT_ID?: string;
  /** Scopes to ask for; "openid profile" by default. */
  readonly VITE_OIDC_SCOPE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
