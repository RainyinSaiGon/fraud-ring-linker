H2-only migrations go here. There are none: H2 has no trigger language, so the
append-only guard on `audit_events` (see `../postgresql`) exists in PostgreSQL only.
The `h2` profile is for local demos and fast tests, not production.
