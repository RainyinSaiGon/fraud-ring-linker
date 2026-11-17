export declare function isLocalRedirect(uri: string): boolean;
export declare function startIssuer(options: {
  port: number;
  keysDir: string;
  accessMinutes?: number;
  host?: string;
}): Promise<{ issuer: string; close: () => Promise<void> }>;
