// Types for the parts of scenario.mjs that TypeScript callers use (the analyst
// page's end-to-end test). The simulator itself is plain JavaScript.

export interface SimAccount {
  account_id: string;
  email: string;
  device_id: string | null;
  ip: string;
  payment_fingerprint: string | null;
  created_at: string;
}

export interface Scenario {
  accounts: SimAccount[];
  truth: {
    seed: number;
    runId: string;
    customers: string[];
    households: { ip: string; accountIds: string[] }[];
    office: { ip: string; accountIds: string[] } | null;
    rings: { ringId: string; accountIds: string[] }[];
  };
}

export interface ScenarioOptions {
  seed?: number;
  runId?: string;
  customers?: number;
  households?: number;
  officeWorkers?: number;
  rings?: number;
  start?: string;
}

export function buildScenario(options?: ScenarioOptions): Scenario;
