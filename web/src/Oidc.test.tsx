import { act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { testToken } from "./test/fakeApi";

// A fake identity-provider client: records what the page asks of it.
const fake = vi.hoisted(() => {
  const handlers: Record<string, (arg?: unknown) => void> = {};
  return {
    handlers,
    settings: undefined as Record<string, unknown> | undefined,
    manager: {
      signinRedirect: vi.fn(async (_args?: unknown) => {}),
      signinRedirectCallback: vi.fn(),
      getUser: vi.fn(async () => ({ id_token: "id-token-1" })),
      removeUser: vi.fn(async () => {}),
      signoutRedirect: vi.fn(async (_args?: unknown) => {}),
      events: {
        addUserLoaded: (cb: (arg?: unknown) => void) => (handlers.loaded = cb),
        addAccessTokenExpired: (cb: () => void) => (handlers.expired = cb),
        addSilentRenewError: (cb: () => void) => (handlers.renewError = cb),
      },
    },
  };
});

vi.mock("oidc-client-ts", () => ({
  UserManager: vi.fn(function (this: unknown, settings: Record<string, unknown>) {
    fake.settings = settings;
    return fake.manager;
  }),
  WebStorageStateStore: vi.fn(),
  InMemoryWebStorage: vi.fn(),
}));

/** Loads the page's modules afresh, with the identity provider configured. */
async function load() {
  vi.resetModules();
  vi.stubEnv("VITE_OIDC_ISSUER", "https://idp.test");
  vi.stubEnv("VITE_OIDC_CLIENT_ID", "analyst-page");
  const react = await import("react");
  const app = await import("./App");
  const auth = await import("./auth");
  const oidc = await import("./oidc");
  return { react, ...app, auth, oidc };
}

beforeEach(async () => {
  Object.values(fake.manager).forEach((f) => typeof f === "function" && "mockClear" in f && f.mockClear());
  // The global setup signs in with a pasted token; these tests start signed out.
  (await import("./auth")).signOut();
  window.history.replaceState(null, "", "/");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("sign-in through the identity provider", () => {
  it("offers it on the sign-in screen and sends the browser there, remembering the route", async () => {
    const { oidc } = await load();
    expect(oidc.oidcConfigured).toBe(true);
    window.location.hash = "#/clusters/c7";

    await oidc.startSignIn();

    expect(fake.manager.signinRedirect).toHaveBeenCalledWith({ state: { hash: "#/clusters/c7" } });
    expect(fake.settings).toMatchObject({
      authority: "https://idp.test",
      client_id: "analyst-page",
      response_type: "code",
      redirect_uri: `${window.location.origin}/`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      automaticSilentRenew: true,
    });
    expect(fake.settings).not.toHaveProperty("client_secret");
  });

  it("finishes signing in on the way back, keeps the token in memory only, and restores the route", async () => {
    const { oidc, auth } = await load();
    const token = testToken({ sub: "u1", preferred_username: "oidc-ana", roles: ["analyst"] });
    fake.manager.signinRedirectCallback.mockResolvedValue({ access_token: token, state: { hash: "#/clusters/c7" } });
    window.history.replaceState(null, "", "/?code=abc&state=xyz");

    expect(await oidc.completeSignIn()).toBeNull();

    expect(auth.currentSession()?.name).toBe("oidc-ana");
    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe("/#/clusters/c7");
    expect(sessionStorage.getItem("linker.token")).toBeNull();
  });

  it("does nothing on an ordinary page load", async () => {
    const { oidc } = await load();
    window.history.replaceState(null, "", "/?state=only");

    expect(await oidc.completeSignIn()).toBeNull();
    expect(fake.manager.signinRedirectCallback).not.toHaveBeenCalled();
  });

  it("says so when the sign-in didn't finish, and cleans the address", async () => {
    const { oidc, auth } = await load();
    fake.manager.signinRedirectCallback.mockRejectedValue(new Error("state mismatch"));
    window.history.replaceState(null, "", "/?error=access_denied&state=xyz");

    expect(await oidc.completeSignIn()).toBe("Sign-in didn't finish. Try again.");
    expect(window.location.search).toBe("");
    expect(auth.currentSession()).toBeNull();
  });

  it("uses each refreshed token, and signs out when renewal fails or the token expires", async () => {
    const { oidc, auth } = await load();
    fake.manager.signinRedirectCallback.mockResolvedValue({ access_token: testToken({ sub: "first" }), state: {} });
    window.history.replaceState(null, "", "/?code=abc&state=xyz");
    await oidc.completeSignIn();

    fake.handlers.loaded!({ access_token: testToken({ sub: "refreshed" }) });
    expect(auth.currentSession()?.name).toBe("refreshed");

    fake.handlers.renewError!();
    expect(auth.currentSession()).toBeNull();
    expect(auth.signOutReason()).toBe("Your session has ended. Sign in again.");

    fake.handlers.loaded!({ access_token: testToken({ sub: "again" }) });
    fake.handlers.expired!();
    expect(auth.currentSession()).toBeNull();
  });

  it("signs out of the identity provider too", async () => {
    const { oidc, auth } = await load();
    fake.manager.signinRedirectCallback.mockResolvedValue({ access_token: testToken(), state: {} });
    window.history.replaceState(null, "", "/?code=abc&state=xyz");
    await oidc.completeSignIn();

    await oidc.signOutEverywhere();

    expect(auth.currentSession()).toBeNull();
    expect(fake.manager.removeUser).toHaveBeenCalled();
    expect(fake.manager.signoutRedirect).toHaveBeenCalledWith({ id_token_hint: "id-token-1" });
  });

  it("shows the provider button, and the paste form only because this is a development build", async () => {
    const { App, react } = await load();
    const { createRoot } = await import("react-dom/client");
    const container = document.createElement("div");
    document.body.append(container);
    const root = createRoot(container);
    await act(async () => root.render(react.createElement(App)));

    expect(container.textContent).toContain("Sign in through your identity provider");
    expect(container.textContent).toContain("Development: paste a token");
    await act(async () => root.unmount());
    container.remove();
  });
});

describe("production builds", () => {
  it("ignore pasted tokens and refuse to keep one", async () => {
    vi.resetModules();
    vi.stubEnv("DEV", false);
    sessionStorage.setItem("linker.token", testToken());
    const auth = await import("./auth");

    expect(auth.currentSession()).toBeNull();
    expect(auth.signIn(testToken())).toEqual({ error: "Pasted tokens work in development builds only." });
    sessionStorage.clear();
  });
});
