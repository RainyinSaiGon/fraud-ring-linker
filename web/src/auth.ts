// The bearer token the page sends with every API call.
//
// For now an analyst pastes a token (from the identity provider or tools/dev-auth);
// real sign-in through the identity provider replaces that screen later. The token
// lives in sessionStorage, so it lasts for this browser tab only and is gone when the
// tab closes. The page reads the token's claims only to show who is signed in and
// when it expires; the service checks the signature.

const KEY = "linker.token";
const listeners = new Set<() => void>();
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

export function signIn(token: string): Session | { error: string } {
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

/** Signs out; `reason` is shown on the sign-in screen, for example when the service refused the token. */
export function signOut(reason: string | null = null) {
  endedBecause = reason;
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // Nothing stored.
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
