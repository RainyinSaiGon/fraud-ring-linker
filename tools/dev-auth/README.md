# Local tokens (development only)

The service checks a bearer token on every API call, and a real deployment gets
those tokens from the bank's identity provider. For local runs, demos and tests,
this script plays that part. It has no dependencies (Node 20+).

```bash
# A key pair in a folder outside the repository; prints the flags that point the service at it
node tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys
# A token for 60 minutes (or --minutes N) with one or more roles: ingest, analyst, auditor
node tools/dev-auth/dev-auth.mjs token --keys /tmp/linker-keys --sub alice --roles analyst
```

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
- The end-to-end tests import `ensureKeys` and `mintToken` from `dev-auth.mjs`.

Tests: `npm test` in this folder.
