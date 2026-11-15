// Types for transfers.mjs, for TypeScript callers (the analyst page's end-to-end test).

import type { Scenario } from "./scenario.mjs";

export interface SimTransfer {
  transfer_id: string;
  from_account: string;
  to_account: string;
  amount_cents: number;
  occurred_at: string;
}

export interface MuleChain {
  chain_id: string;
  /** In hop order: the first receives from the victims, the last pays the cash-out. */
  account_ids: string[];
  victims: number;
  cash_out: string;
}

export interface TransferCase {
  case: "small_business" | "own_money" | "payday_bills";
  account_id: string;
}

export interface TransferOptions {
  muleChains?: number;
  muleChainMinLength?: number;
  muleChainMaxLength?: number;
  smallBusinesses?: number;
  ownMoney?: number;
  paydayBills?: number;
  transferDays?: number;
}

export const TRANSFER_DEFAULTS: Readonly<Required<TransferOptions>>;

export function buildTransfers(
  scenario: Scenario,
  options?: TransferOptions,
): { transfers: SimTransfer[]; muleChains: MuleChain[]; cases: TransferCase[] };
