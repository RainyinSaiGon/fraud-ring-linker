import { currentSession } from "../auth";

/** Who the service will record as deciding: the signed-in analyst, from the token. */
export function DecidingAs() {
  const name = currentSession()?.name;
  if (!name) return null;
  return (
    <p className="muted small">
      Deciding as <strong>{name}</strong>
    </p>
  );
}
