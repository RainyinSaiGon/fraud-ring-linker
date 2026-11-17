# Local tokens (development only)

The service checks a bearer token on every API call, and a real deployment gets
those tokens from the bank's identity provider. For local runs, demos and tests,
this script plays that part. It has no dependencies (Node 20+).

```bash
# A key pair in a folder outside the repository; prints the flags that point the service at it
node tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys
# A token for 60 minutes (or --minutes N) with one or more roles: ingest, analyst, auditor
node tools/dev-auth/dev-auth.mjs token --keys /tmp/linker-keys --sub alice --roles analyst
# A local OpenID Connect provider for the analyst page's sign-in, on port 18090 (or --port N)
node tools/dev-auth/dev-auth.mjs issuer --keys /tmp/linker-keys
```

## Local identity provider

`issuer` runs a tiny OpenID Connect provider (`issuer.mjs`) so the analyst page
can sign in the way it would at a bank: the authorization code flow with PKCE
(S256 only), a token endpoint, rotating refresh tokens, signing keys (JWKS) and a
logout redirect. Its sign-in form asks for a name and roles; **there are no
passwords**, so anyone who can reach it can be anyone. It only sends codes and
logouts back to `localhost`, and it signs with the same key pair as `token`, so
tokens from either work.

Its issuer is its own URL (`http://localhost:18090`), so give that to the
service and to `token`: `keys <dir> --issuer http://localhost:18090` prints the
matching service flags. The end-to-end tests start it themselves.

Start the service with the printed flags:

```bash
java -jar java/service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2 \
  $(node tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys)
```

- `keys` keeps an existing pair, so running it again prints the same flags.
- Tokens are RS256 JWTs with `iss` `http://localhost/dev-auth`, `aud`
  `fraud-ring-linker`, `sub` and `preferred_username` set to `--sub`, `roles`,
  `iat`, `exp` and `jti`.
- **Never commit the key folder,** and never point a real deployment at it.
  Anyone holding `private.pem` can sign in as anyone.
- The end-to-end tests import `ensureKeys` and `mintToken` from `dev-auth.mjs`,
  and run the issuer.

Tests: `npm test` in this folder.
