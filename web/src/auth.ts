// The bearer token the page sends with every API call.
//
// It comes from one of two places:
// - Sign-in through the identity provider (oidc.ts). The token is kept in memory
//   only, so it's gone when the page is reloaded or closed; signing in again is
//   quick while the identity provider's own session lasts.
// - In development builds only, a pasted token (from tools/dev-auth), kept in
//   sessionStorage for this tab. Production builds never read it.
//
// The page reads the token's claims only to show who is signed in, what their
// roles allow on screen, and when it expires; the service checks the signature.

const KEY = "linker.token";
const PASTE_ALLOWED = import.meta.env.DEV;
const listeners = new Set<() => void>();
let memoryToken: string | null = null;
let endedBecause: string | null = null;

export interface Session {
  token: string;
  /** `preferred_username`, falling back to `sub`. */
  name: string;
  roles: string[];
  /** Milliseconds since the epoch. */
  expiresAt: number;
}

/** The token's claims, or null when it isn't a JWT with a name and an expiry. Never verifies it. */
export function readToken(token: string, now = Date.now()): Session | { error: string } {
  const parts = token.trim().split(".");
  if (parts.length !== 3) return { error: "That isn't a token. A token has three parts separated by dots." };
  let claims: Record<string, unknown>;
  try {
    const binary = atob(parts[1]!.replace(/-/g, "+").replace(/_/g, "/"));
    const json = new TextDecoder().decode(Uint8Array.from(binary, (c) => c.charCodeAt(0)));
    claims = JSON.parse(json) as Record<string, unknown>;
  } catch {
    return { error: "That token can't be read." };
  }
  const name = typeof claims.preferred_username === "string" ? claims.preferred_username : claims.sub;
  if (typeof name !== "string" || name === "") return { error: "That token names nobody." };
  if (typeof claims.exp !== "number") return { error: "That token has no expiry." };
  const expiresAt = claims.exp * 1000;
  if (expiresAt <= now) return { error: "That token has expired. Get a new one." };
  const roles = Array.isArray(claims.roles) ? claims.roles.filter((r): r is string => typeof r === "string") : [];
  return { token: token.trim(), name, roles: roles.map((r) => r.toLowerCase()), expiresAt };
}

function stored(): string | null {
  if (memoryToken) return memoryToken;
  if (!PASTE_ALLOWED) return null;
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** The current session, or null when nobody is signed in or the token has expired. */
export function currentSession(now = Date.now()): Session | null {
  const token = stored();
  if (!token) return null;
  const session = readToken(token, now);
  return "error" in session ? null : session;
}

/** A pasted token. Development builds only. */
export function signIn(token: string): Session | { error: string } {
  if (!PASTE_ALLOWED) return { error: "Pasted tokens work in development builds only." };
  const session = readToken(token);
  if ("error" in session) return session;
  endedBecause = null;
  try {
    sessionStorage.setItem(KEY, session.token);
  } catch {
    return { error: "This browser won't keep the token. Allow site storage and try again." };
  }
  listeners.forEach((listener) => listener());
  return session;
}

/** A token from the identity provider, kept in memory only. `null` forgets it. */
export function setProviderToken(token: string | null) {
  memoryToken = token;
  if (token) endedBecause = null;
  listeners.forEach((listener) => listener());
}

/** Whether the current session is allowed to make decisions. The service enforces it; this only hides forms. */
export function canDecide(): boolean {
  return currentSession()?.roles.includes("analyst") ?? false;
}

/**
 * Forgets the token on this page; `reason` is shown on the sign-in screen, for
 * example when the service refused the token. `oidc.signOutEverywhere` also ends
 * the identity provider's session.
 */
export function signOut(reason: string | null = null) {
  endedBecause = reason;
  memoryToken = null;
  if (PASTE_ALLOWED) {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      // Nothing stored.
    }
  }
  listeners.forEach((listener) => listener());
}

/** Why the last session ended, when it wasn't the analyst's own choice. */
export function signOutReason(): string | null {
  return endedBecause;
}

/** Called on every sign-in and sign-out. Returns a function that stops listening. */
export function onSessionChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
