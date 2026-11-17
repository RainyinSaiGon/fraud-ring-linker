// A tiny OpenID Connect provider, for local runs and the end-to-end tests only.
//
// It does just enough of the authorization code flow with PKCE for the analyst
// page to sign in the way it would against a bank's identity provider: a sign-in
// form where you type a name and tick roles, a token endpoint, refresh tokens,
// signing keys (JWKS) and a logout redirect. There are no passwords: anyone who can
// reach it can be anyone. It only redirects back to localhost.

import { createHash, createPublicKey, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { DEV_AUDIENCE, ROLES, ensureKeys, mintToken } from "./dev-auth.mjs";

const CODE_TTL_MS = 60_000;
const random = () => randomBytes(24).toString("base64url");

/** Only local pages may receive codes or be sent back to after logout. */
export function isLocalRedirect(uri) {
  try {
    const url = new URL(uri);
    return (url.protocol === "http:" || url.protocol === "https:")
      && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  } catch {
    return false;
  }
}

const escapeHtml = (value) =>
  String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/**
 * Starts the issuer.
 *
 * @param {{port: number, keysDir: string, accessMinutes?: number, host?: string}} options
 * @returns {Promise<{issuer: string, close: () => Promise<void>}>}
 */
export async function startIssuer({ port, keysDir, accessMinutes = 10, host = "localhost" }) {
  const keys = ensureKeys(keysDir);
  const privatePem = readFileSync(keys.privateKey, "utf8");
  const jwk = createPublicKey(readFileSync(keys.publicKey, "utf8")).export({ format: "jwk" });
  const kid = createHash("sha256").update(jwk.n).digest("base64url").slice(0, 16);
  const issuer = `http://${host}:${port}`;
  const codes = new Map();
  const refreshTokens = new Map();

  function tokens({ sub, roles, clientId, nonce }) {
    const common = { sub, issuer, minutes: accessMinutes };
    const accessToken = mintToken(privatePem, { ...common, roles, audience: DEV_AUDIENCE, claims: { azp: clientId } });
    const idToken = mintToken(privatePem, {
      ...common,
      audience: clientId,
      claims: { roles: undefined, nonce, azp: clientId },
    });
    const refreshToken = random();
    refreshTokens.set(refreshToken, { sub, roles, clientId });
    return {
      access_token: accessToken,
      id_token: idToken,
      refresh_token: refreshToken,
      token_type: "Bearer",
      expires_in: accessMinutes * 60,
      scope: "openid profile",
    };
  }

  function send(res, status, body, headers = {}) {
    res.writeHead(status, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Cache-Control": "no-store",
      ...headers,
    });
    res.end(body);
  }
  const json = (res, status, value) => send(res, status, JSON.stringify(value), { "Content-Type": "application/json" });
  const oauthError = (res, error, description) => json(res, 400, { error, error_description: description });

  async function form(req) {
    let body = "";
    for await (const chunk of req) {
      body += chunk;
      if (body.length > 64_000) throw new Error("too large");
    }
    return new URLSearchParams(body);
  }

  function signInPage(params) {
    const hidden = ["client_id", "redirect_uri", "state", "nonce", "code_challenge", "scope"]
      .map((name) => `<input type="hidden" name="${name}" value="${escapeHtml(params.get(name) ?? "")}">`)
      .join("");
    const roles = ROLES.map((role) =>
      `<label><input type="checkbox" name="roles" value="${role}"${role === "analyst" ? " checked" : ""}> ${role}</label>`,
    ).join(" ");
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Local sign-in</title>
<meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="font-family: system-ui, sans-serif; max-width: 28rem; margin: 3rem auto; padding: 0 1rem">
<h1>Local sign-in</h1>
<p>Development identity provider. There are no passwords: never use it for real data.</p>
<form method="post" action="/authorize">${hidden}
<p><label>Name <input name="username" required maxlength="128" autocomplete="off"></label></p>
<fieldset><legend>Roles</legend>${roles}</fieldset>
<p><button type="submit">Sign in</button></p></form></body></html>`;
  }

  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, issuer);
      if (req.method === "OPTIONS") return send(res, 204, "");
      if (req.method === "GET" && url.pathname === "/.well-known/openid-configuration") {
        return json(res, 200, {
          issuer,
          authorization_endpoint: `${issuer}/authorize`,
          token_endpoint: `${issuer}/token`,
          jwks_uri: `${issuer}/jwks`,
          end_session_endpoint: `${issuer}/logout`,
          response_types_supported: ["code"],
          grant_types_supported: ["authorization_code", "refresh_token"],
          code_challenge_methods_supported: ["S256"],
          subject_types_supported: ["public"],
          id_token_signing_alg_values_supported: ["RS256"],
          token_endpoint_auth_methods_supported: ["none"],
          scopes_supported: ["openid", "profile"],
        });
      }
      if (req.method === "GET" && url.pathname === "/jwks") {
        return json(res, 200, { keys: [{ ...jwk, kid, use: "sig", alg: "RS256" }] });
      }
      if (req.method === "GET" && url.pathname === "/authorize") {
        const p = url.searchParams;
        if (p.get("response_type") !== "code") return oauthError(res, "unsupported_response_type", "Only code is supported.");
        if (!p.get("client_id")) return oauthError(res, "invalid_request", "client_id is required.");
        if (!isLocalRedirect(p.get("redirect_uri"))) return oauthError(res, "invalid_request", "redirect_uri must be local.");
        if (p.get("code_challenge_method") !== "S256" || !p.get("code_challenge")) {
          return oauthError(res, "invalid_request", "PKCE with S256 is required.");
        }
        return send(res, 200, signInPage(p), { "Content-Type": "text/html; charset=utf-8" });
      }
      if (req.method === "POST" && url.pathname === "/authorize") {
        const p = await form(req);
        const username = (p.get("username") ?? "").trim();
        const roles = p.getAll("roles").filter((r) => ROLES.includes(r));
        if (!username || username.length > 128) return oauthError(res, "invalid_request", "A name of 1 to 128 characters is required.");
        if (!isLocalRedirect(p.get("redirect_uri"))) return oauthError(res, "invalid_request", "redirect_uri must be local.");
        const code = random();
        codes.set(code, {
          sub: username, roles, clientId: p.get("client_id"), redirectUri: p.get("redirect_uri"),
          nonce: p.get("nonce") || undefined, challenge: p.get("code_challenge"), expires: Date.now() + CODE_TTL_MS,
        });
        const back = new URL(p.get("redirect_uri"));
        back.searchParams.set("code", code);
        if (p.get("state")) back.searchParams.set("state", p.get("state"));
        return send(res, 302, "", { Location: back.toString() });
      }
      if (req.method === "POST" && url.pathname === "/token") {
        const p = await form(req);
        if (p.get("grant_type") === "authorization_code") {
          const grant = codes.get(p.get("code") ?? "");
          codes.delete(p.get("code") ?? "");
          if (!grant || grant.expires < Date.now()) return oauthError(res, "invalid_grant", "Unknown or expired code.");
          if (grant.clientId !== p.get("client_id") || grant.redirectUri !== p.get("redirect_uri")) {
            return oauthError(res, "invalid_grant", "client_id or redirect_uri doesn't match.");
          }
          const verifier = p.get("code_verifier") ?? "";
          if (createHash("sha256").update(verifier).digest("base64url") !== grant.challenge) {
            return oauthError(res, "invalid_grant", "PKCE verification failed.");
          }
          return json(res, 200, tokens(grant));
        }
        if (p.get("grant_type") === "refresh_token") {
          const session = refreshTokens.get(p.get("refresh_token") ?? "");
          refreshTokens.delete(p.get("refresh_token") ?? "");
          if (!session || session.clientId !== p.get("client_id")) {
            return oauthError(res, "invalid_grant", "Unknown refresh token.");
          }
          return json(res, 200, tokens(session));
        }
        return oauthError(res, "unsupported_grant_type", "Use authorization_code or refresh_token.");
      }
      if (req.method === "GET" && url.pathname === "/logout") {
        const back = url.searchParams.get("post_logout_redirect_uri");
        if (back && isLocalRedirect(back)) return send(res, 302, "", { Location: back });
        return send(res, 200, "Signed out.", { "Content-Type": "text/plain; charset=utf-8" });
      }
      return send(res, 404, "Not found.", { "Content-Type": "text/plain; charset=utf-8" });
    } catch {
      return send(res, 400, "Bad request.", { "Content-Type": "text/plain; charset=utf-8" });
    }
  });
  await new Promise((resolve) => server.listen(port, host, resolve));
  return { issuer, close: () => new Promise((resolve) => server.close(() => resolve())) };
}
