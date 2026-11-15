// Thin HTTP client for the fraud-ring-linker REST API.

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

/**
 * @param {string} baseUrl   e.g. http://localhost:8080
 * @param {typeof fetch} fetchImpl  injectable for tests
 */
export function createClient(baseUrl, fetchImpl = fetch) {
  const root = baseUrl.replace(/\/+$/, "");

  async function request(method, path, body) {
    const response = await fetchImpl(`${root}${path}`, {
      method,
      headers: body === undefined ? { Accept: "application/json" } : { "Content-Type": "application/json", Accept: "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    const json = text ? safeJson(text) : null;
    if (!response.ok) {
      throw new ApiError(response.status, json);
    }
    return json;
  }

  return {
    registerAccount: (account) => request("POST", "/api/v1/accounts", account),
    recordTransfers: (transfers) => request("POST", "/api/v1/transfers", { transfers }),
    reviewQueue: (limit = 500) => request("GET", `/api/v1/clusters?limit=${limit}`),
    cluster: (clusterId) => request("GET", `/api/v1/clusters/${encodeURIComponent(clusterId)}`),
    decide: (clusterId, outcome, analyst, note) =>
      request("POST", `/api/v1/clusters/${encodeURIComponent(clusterId)}/decisions`, { outcome, analyst, note }),
    health: () => request("GET", "/actuator/health"),
  };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text.slice(0, 200) };
  }
}

/** The most transfers the service takes in one request. */
export const TRANSFER_BATCH = 500;

/**
 * Sends transfers in time order, in batches the service accepts, one batch at a time.
 *
 * @returns {{sent: number, flagged: string[]}} accounts flagged as likely mules, in the order they were flagged
 */
export async function sendTransfers(client, transfers, { onProgress } = {}) {
  const flagged = [];
  let sent = 0;
  for (let start = 0; start < transfers.length; start += TRANSFER_BATCH) {
    const batch = transfers.slice(start, start + TRANSFER_BATCH);
    const response = await client.recordTransfers(batch);
    flagged.push(...response.flagged_now);
    sent += batch.length;
    onProgress?.(sent, transfers.length);
  }
  return { sent, flagged };
}

/**
 * Registers accounts with a bounded number of requests in flight.
 * Returns one result per account, in input order: { account, response } or { account, error }.
 */
export async function registerAll(client, accounts, { concurrency = 8, onProgress } = {}) {
  const results = new Array(accounts.length);
  let nextIndex = 0;
  let done = 0;
  async function worker() {
    while (nextIndex < accounts.length) {
      const index = nextIndex++;
      const account = accounts[index];
      try {
        results[index] = { account, response: await client.registerAccount(account) };
      } catch (error) {
        results[index] = { account, error };
      }
      done += 1;
      onProgress?.(done, accounts.length);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, accounts.length) }, worker));
  return results;
}
