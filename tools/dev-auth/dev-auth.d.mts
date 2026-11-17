export declare const DEV_ISSUER: string;
export declare const DEV_AUDIENCE: string;
export declare const ROLES: readonly ["ingest", "analyst", "auditor"];
export type Role = (typeof ROLES)[number];

export declare function ensureKeys(dir: string): { privateKey: string; publicKey: string };

export declare function mintToken(
  privateKeyPem: string,
  options: {
    sub: string;
    roles?: Role[];
    issuer?: string;
    audience?: string;
    minutes?: number;
    now?: number;
    claims?: Record<string, unknown>;
  },
): string;

export declare function serviceArgs(publicKeyPath: string): string[];

export declare function main(argv: string[], out?: (line: string) => void): void;
