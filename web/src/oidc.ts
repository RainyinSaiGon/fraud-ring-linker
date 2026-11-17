// Sign-in through the identity provider: OpenID Connect, authorization code flow
// with PKCE. The issuer and client ID are build settings; a browser can't keep a
// secret, so the client is public and PKCE protects the code. Tokens stay in
// memory (see auth.ts) and are refreshed shortly before they expire.

import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { signOut, setProviderToken } from "./auth";

const issuer = import.meta.env.VITE_OIDC_ISSUER;
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID;

/** Whether this build can sign in through an identity provider. */
export const oidcConfigured = Boolean(issuer && clientId);

const ENDED = "Your session has ended. Sign in again.";
let manager: UserManager | null = null;

function here(): string {
  return window.location.origin + window.location.pathname;
}

function userManager(): UserManager {
  if (!oidcConfigured) throw new Error("Sign-in isn't set up for this build.");
  if (!manager) {
    manager = new UserManager({
      authority: issuer!,
      client_id: clientId!,
      redirect_uri: here(),
      post_logout_redirect_uri: here(),
      response_type: "code",
      scope: import.meta.env.VITE_OIDC_SCOPE ?? "openid profile",
      // Tokens live in memory only. The sign-in round trip itself (state and the
      // PKCE verifier) must survive the redirect, so it stays in sessionStorage.
      userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
      automaticSilentRenew: true,
      loadUserInfo: false,
    });
    manager.events.addUserLoaded((user: User) => setProviderToken(user.access_token));
    manager.events.addAccessTokenExpired(() => signOut(ENDED));
    manager.events.addSilentRenewError(() => signOut(ENDED));
  }
  return manager;
}

/** Sends the browser to the identity provider. It comes back to the same page and route. */
export async function startSignIn(): Promise<void> {
  await userManager().signinRedirect({ state: { hash: window.location.hash } });
}

/**
 * Finishes a sign-in when the identity provider has just sent the browser back
 * (with `code` and `state` in the address). Returns a message when it failed, and
 * null otherwise, including when there was nothing to finish.
 */
export async function completeSignIn(): Promise<string | null> {
  const params = new URLSearchParams(window.location.search);
  if (!oidcConfigured || !params.has("state") || !(params.has("code") || params.has("error"))) return null;
  try {
    const user = await userManager().signinRedirectCallback();
    setProviderToken(user.access_token);
    const hash = (user.state as { hash?: string } | undefined)?.hash ?? "";
    window.history.replaceState(null, "", window.location.pathname + hash);
    return null;
  } catch {
    window.history.replaceState(null, "", window.location.pathname);
    return "Sign-in didn't finish. Try again.";
  }
}

/** Signs out of this page and, when signed in through it, of the identity provider too. */
export async function signOutEverywhere(): Promise<void> {
  signOut();
  if (!manager) return;
  try {
    const user = await manager.getUser();
    await manager.removeUser();
    await manager.signoutRedirect({ id_token_hint: user?.id_token });
  } catch {
    // The page is signed out either way.
  }
}
