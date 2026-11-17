import assert from "node:assert/strict";
import { createPublicKey, verify } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { DEV_AUDIENCE, DEV_ISSUER, ensureKeys, main, mintToken, serviceArgs } from "../dev-auth.mjs";

const dir = mkdtempSync(join(tmpdir(), "dev-auth-test-"));
after(() => rmSync(dir, { recursive: true, force: true }));

function decode(token) {
  const [header, payload, signature] = token.split(".");
  return {
    header: JSON.parse(Buffer.from(header, "base64url").toString()),
    payload: JSON.parse(Buffer.from(payload, "base64url").toString()),
    signingInput: `${header}.${payload}`,
    signature: Buffer.from(signature, "base64url"),
  };
}

test("makes a key pair once and keeps it", () => {
  const first = ensureKeys(join(dir, "keys"));
  const pem = readFileSync(first.privateKey, "utf8");
  const second = ensureKeys(join(dir, "keys"));

  assert.equal(readFileSync(second.privateKey, "utf8"), pem);
  assert.match(readFileSync(first.publicKey, "utf8"), /^-----BEGIN PUBLIC KEY-----/);
  if (process.platform !== "win32") {
    assert.equal(statSync(first.privateKey).mode & 0o077, 0, "only the owner can read the private key");
  }
});

test("signs an RS256 token the public key verifies, with the claims the service checks", () => {
  const keys = ensureKeys(join(dir, "keys"));
  const token = mintToken(readFileSync(keys.privateKey, "utf8"), {
    sub: "alice",
    roles: ["analyst"],
    minutes: 5,
    now: 1_800_000_000_000,
  });
  const { header, payload, signingInput, signature } = decode(token);

  assert.deepEqual(header, { alg: "RS256", typ: "JWT" });
  assert.equal(payload.iss, DEV_ISSUER);
  assert.equal(payload.aud, DEV_AUDIENCE);
  assert.equal(payload.sub, "alice");
  assert.equal(payload.preferred_username, "alice");
  assert.deepEqual(payload.roles, ["analyst"]);
  assert.equal(payload.iat, 1_800_000_000);
  assert.equal(payload.exp, 1_800_000_300);
  assert.ok(payload.jti);
  const publicKey = createPublicKey(readFileSync(keys.publicKey, "utf8"));
  assert.ok(verify("RSA-SHA256", Buffer.from(signingInput), publicKey, signature));
});

test("tests can override or drop any claim", () => {
  const keys = ensureKeys(join(dir, "keys"));
  const { payload } = decode(mintToken(readFileSync(keys.privateKey, "utf8"), {
    sub: "bob",
    claims: { iss: "https://elsewhere.test", exp: undefined },
  }));

  assert.equal(payload.iss, "https://elsewhere.test");
  assert.equal("exp" in payload, false);
});

test("refuses a token with no subject, an unknown role or no lifetime", () => {
  const pem = readFileSync(ensureKeys(join(dir, "keys")).privateKey, "utf8");

  assert.throws(() => mintToken(pem, { sub: "" }), /subject/);
  assert.throws(() => mintToken(pem, { sub: "a", roles: ["admin"] }), /unknown role "admin"/);
  assert.throws(() => mintToken(pem, { sub: "a", minutes: 0 }), /minutes/);
});

test("the command line makes keys, prints the service flags and mints tokens", () => {
  const lines = [];
  main(["keys", join(dir, "cli")], (line) => lines.push(line));
  main(["token", "--keys", join(dir, "cli"), "--sub", "carol", "--roles", "ingest, auditor"], (line) => lines.push(line));

  assert.equal(lines[0], serviceArgs(join(dir, "cli", "public.pem")).join(" "));
  assert.match(lines[0], /--linker\.security\.public-key=file:.*public\.pem$/);
  assert.doesNotMatch(lines[0], /\\/);
  assert.deepEqual(decode(lines[1]).payload.roles, ["ingest", "auditor"]);
  assert.throws(() => main(["token", "--keys", join(dir, "cli")]), /Usage/);
  assert.throws(() => main(["what"]), /Usage/);
});
