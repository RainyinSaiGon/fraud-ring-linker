import assert from "node:assert/strict";
import { createHash, createPublicKey, verify } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";
import { isLocalRedirect, startIssuer } from "../issuer.mjs";

const dir = mkdtempSync(join(tmpdir(), "dev-issuer-test-"));
let issuer;
let close;
const redirect = "http://localhost:5173/";
const verifier = "a-long-random-verifier-string-for-the-pkce-test-0123456789";
const challenge = createHash("sha256").update(verifier).digest("base64url");

before(async () => {
  ({ issuer, close } = await startIssuer({ port: 18_199, keysDir: dir, accessMinutes: 5 }));
});
after(async () => {
  await close();
  rmSync(dir, { recursive: true, force: true });
});

const decode = (jwt) => JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString());

function authorizeUrl(overrides = {}) {
  const url = new URL(`${issuer}/authorize`);
  const params = {
    response_type: "code", client_id: "analyst-page", redirect_uri: redirect, state: "s1", nonce: "n1",
    code_challenge: challenge, code_challenge_method: "S256", scope: "openid profile", ...overrides,
  };
  for (const [key, value] of Object.entries(params)) if (value !== undefined) url.searchParams.set(key, value);
  return url;
}

async function signIn(username, roles, overrides = {}) {
  const body = new URLSearchParams({
    client_id: "analyst-page", redirect_uri: redirect, state: "s1", nonce: "n1", code_challenge: challenge,
    username, ...overrides,
  });
  roles.forEach((role) => body.append("roles", role));
  const response = await fetch(`${issuer}/authorize`, { method: "POST", body, redirect: "manual" });
  return response;
}

async function exchange(code, overrides = {}) {
  const body = new URLSearchParams({
    grant_type: "authorization_code", code, client_id: "analyst-page", redirect_uri: redirect, code_verifier: verifier,
    ...overrides,
  });
  return fetch(`${issuer}/token`, { method: "POST", body });
}

test("publishes discovery and signing keys", async () => {
  const config = await (await fetch(`${issuer}/.well-known/openid-configuration`)).json();
  assert.equal(config.issuer, issuer);
  assert.equal(config.token_endpoint, `${issuer}/token`);
  assert.deepEqual(config.code_challenge_methods_supported, ["S256"]);
  const { keys } = await (await fetch(config.jwks_uri)).json();
  assert.equal(keys.length, 1);
  assert.equal(keys[0].alg, "RS256");
});

test("shows a sign-in form, escaping what the page sent", async () => {
  const response = await fetch(authorizeUrl({ state: `"><script>alert(1)</script>` }));
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /<form method="post" action="\/authorize">/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&quot;&gt;&lt;script&gt;/);
});

test("refuses a request without PKCE, with another response type, or redirecting off this machine", async () => {
  for (const overrides of [
    { code_challenge: undefined },
    { code_challenge_method: "plain" },
    { response_type: "token" },
    { redirect_uri: "https://evil.example/cb" },
    { client_id: undefined },
  ]) {
    const response = await fetch(authorizeUrl(overrides));
    assert.equal(response.status, 400, JSON.stringify(overrides));
  }
  assert.equal((await signIn("ana", ["analyst"], { redirect_uri: "https://evil.example/" })).status, 400);
  assert.equal((await signIn("", ["analyst"])).status, 400);
});

test("signs in, swaps the code for tokens once, and refreshes them", async () => {
  const response = await signIn("ana", ["analyst", "admin"]);
  assert.equal(response.status, 302);
  const back = new URL(response.headers.get("location"));
  assert.equal(back.origin + back.pathname, redirect);
  assert.equal(back.searchParams.get("state"), "s1");
  const code = back.searchParams.get("code");

  assert.equal((await exchange(code, { code_verifier: "wrong" })).status, 400, "a wrong verifier fails");
  const again = await signIn("ana", ["analyst", "admin"]);
  const code2 = new URL(again.headers.get("location")).searchParams.get("code");
  const tokens = await (await exchange(code2)).json();
  assert.equal(tokens.token_type, "Bearer");
  assert.equal(tokens.expires_in, 300);

  const access = decode(tokens.access_token);
  assert.equal(access.iss, issuer);
  assert.equal(access.aud, "fraud-ring-linker");
  assert.equal(access.sub, "ana");
  assert.equal(access.azp, "analyst-page");
  assert.deepEqual(access.roles, ["analyst"], "unknown roles are dropped");
  const id = decode(tokens.id_token);
  assert.equal(id.aud, "analyst-page");
  assert.equal(id.nonce, "n1");
  assert.equal("roles" in id, false);

  const { keys } = await (await fetch(`${issuer}/jwks`)).json();
  const [header, payload, signature] = tokens.access_token.split(".");
  assert.ok(verify("RSA-SHA256", Buffer.from(`${header}.${payload}`), createPublicKey({ key: keys[0], format: "jwk" }),
    Buffer.from(signature, "base64url")));

  assert.equal((await exchange(code2)).status, 400, "a code works once");

  const refresh = (token) => fetch(`${issuer}/token`, {
    method: "POST",
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: token, client_id: "analyst-page" }),
  });
  const renewed = await (await refresh(tokens.refresh_token)).json();
  assert.equal(decode(renewed.access_token).sub, "ana");
  assert.notEqual(renewed.refresh_token, tokens.refresh_token);
  assert.equal((await refresh(tokens.refresh_token)).status, 400, "a refresh token works once");
});

test("logs out back to a local page only", async () => {
  const local = await fetch(`${issuer}/logout?post_logout_redirect_uri=${encodeURIComponent(redirect)}`, { redirect: "manual" });
  assert.equal(local.status, 302);
  assert.equal(local.headers.get("location"), redirect);
  const away = await fetch(`${issuer}/logout?post_logout_redirect_uri=https://evil.example/`, { redirect: "manual" });
  assert.equal(away.status, 200);
});

test("allows browsers on other local ports to call it", async () => {
  const response = await fetch(`${issuer}/token`, { method: "OPTIONS" });
  assert.equal(response.status, 204);
  assert.equal(response.headers.get("access-control-allow-origin"), "*");
});

test("knows which redirects are local", () => {
  assert.ok(isLocalRedirect("http://localhost:5174/"));
  assert.ok(isLocalRedirect("http://127.0.0.1/cb"));
  assert.equal(isLocalRedirect("http://localhost.evil.example/"), false);
  assert.equal(isLocalRedirect("javascript:alert(1)"), false);
  assert.equal(isLocalRedirect("not a url"), false);
});
