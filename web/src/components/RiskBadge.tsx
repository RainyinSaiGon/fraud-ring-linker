import type { RiskAction } from "../api";

const TEXT: Record<RiskAction, string> = {
  ALLOW: "Allow",
  REVIEW: "Needs review",
  RESTRICT: "Restrict",
};

/** Colour plus words, so the action never depends on colour alone. */
export function RiskBadge({ action }: { action: RiskAction }) {
  return <span className={`badge badge-${action.toLowerCase()}`}>{TEXT[action]}</span>;
}
