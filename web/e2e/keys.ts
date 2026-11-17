import { join } from "node:path";
import { tmpdir } from "node:os";
import { ensureKeys } from "../../tools/dev-auth/dev-auth.mjs";

/**
 * The key pair the end-to-end run signs tokens with. It's made on first use in the
 * system temporary folder and reused, so a service left running between local runs
 * still knows it. Never committed.
 */
export const keys = ensureKeys(join(tmpdir(), "fraud-ring-linker-e2e-keys"));
