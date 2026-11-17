#!/usr/bin/env node
// Local tokens for the fraud-ring-linker service, for development, demos and tests only.
//
// A real deployment checks tokens from the bank's identity provider. Locally, this
// script plays that part: it makes an RSA key pair in a folder you choose (never
// commit it) and signs short-lived tokens with it. Point the service at the public
// half:
//
//   node tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys
//   java -jar java/service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2 \
//     --linker.security.issuer=http://localhost/dev-auth --linker.security.audience=fraud-ring-linker \
//     --linker.security.public-key=file:/tmp/linker-keys/public.pem
//   node tools/dev-auth/dev-auth.mjs token --keys /tmp/linker-keys --sub alice --roles analyst
//
// No dependencies: node:crypto signs RS256 itself.

import { createPrivateKey, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";

export const DEV_ISSUER = "http://localhost/dev-auth";
export const DEV_AUDIENCE = "fraud-ring-linker";
export const ROLES = ["ingest", "analyst", "auditor"];

/**
 * Writes `private.pem` and `public.pem` into `dir`, unless both are already there.
 * @returns {{privateKey: string, publicKey: string}} the two file paths
 */
export function ensureKeys(dir) {
  const privateKey = join(dir, "private.pem");
  const publicKey = join(dir, "public.pem");
  if (!existsSync(privateKey) || !existsSync(publicKey)) {
    mkdirSync(dir, { recursive: true });
    const pair = generateKeyPairSync("rsa", {
      modulusLength: 2048,
      publicKeyEncoding: { type: "spki", format: "pem" },
      privateKeyEncoding: { type: "pkcs8", format: "pem" },
    });
    writeFileSync(privateKey, pair.privateKey, { mode: 0o600 });
    writeFileSync(publicKey, pair.publicKey);
  }
  return { privateKey, publicKey };
}

const base64url = (data) => Buffer.from(data).toString("base64url");

/**
 * A signed RS256 JWT.
 *
 * @param {string} privateKeyPem
 * @param {{sub: string, roles?: string[], issuer?: string, audience?: string, minutes?: number,
 *          now?: number, claims?: Record<string, unknown>}} options
 *   `claims` is merged last, so tests can override or remove (with `undefined`) any claim.
 */
export function mintToken(privateKeyPem, options) {
  const { sub, roles = [], issuer = DEV_ISSUER, audience = DEV_AUDIENCE, minutes = 60 } = options;
  if (!sub) throw new Error("a token needs a subject (--sub)");
  if (!(minutes > 0)) throw new Error("--minutes must be above 0");
  for (const role of roles) {
    if (!ROLES.includes(role)) throw new Error(`unknown role "${role}"; use ${ROLES.join(", ")}`);
  }
  const now = Math.floor((options.now ?? Date.now()) / 1000);
  const payload = {
    iss: issuer,
    aud: audience,
    sub,
    preferred_username: sub,
    roles,
    iat: now,
    exp: now + Math.round(minutes * 60),
    jti: randomUUID(),
    ...options.claims,
  };
  const header = { alg: "RS256", typ: "JWT" };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(payload))}`;
  const signature = sign("RSA-SHA256", Buffer.from(signingInput), createPrivateKey(privateKeyPem));
  return `${signingInput}.${signature.toString("base64url")}`;
}

/** Service flags for the dev issuer and the public key in `dir`. */
export function serviceArgs(publicKeyPath) {
  return [
    `--linker.security.issuer=${DEV_ISSUER}`,
    `--linker.security.audience=${DEV_AUDIENCE}`,
    `--linker.security.public-key=file:${resolve(publicKeyPath).replaceAll("\\", "/")}`,
  ];
}

const USAGE = `Usage:
  dev-auth.mjs keys <dir>
      Makes an RSA key pair in <dir> (kept if already there) and prints the service flags.
  dev-auth.mjs token --keys <dir> --sub <name> --roles <role>[,<role>...] [--minutes 60]
      Prints a signed token. Roles: ${ROLES.join(", ")}.`;

export function main(argv, out = console.log) {
  const [command, ...rest] = argv;
  if (command === "keys") {
    if (rest.length !== 1) throw new Error(USAGE);
    const { publicKey } = ensureKeys(rest[0]);
    out(serviceArgs(publicKey).join(" "));
    return;
  }
  if (command === "token") {
    const { values } = parseArgs({
      args: rest,
      options: { keys: { type: "string" }, sub: { type: "string" }, roles: { type: "string" }, minutes: { type: "string" } },
    });
    if (!values.keys || !values.sub || !values.roles) throw new Error(USAGE);
    const pem = readFileSync(join(values.keys, "private.pem"), "utf8");
    out(mintToken(pem, {
      sub: values.sub,
      roles: values.roles.split(",").map((r) => r.trim()).filter(Boolean),
      minutes: values.minutes === undefined ? 60 : Number(values.minutes),
    }));
    return;
  }
  throw new Error(USAGE);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exit(2);
  }
}
