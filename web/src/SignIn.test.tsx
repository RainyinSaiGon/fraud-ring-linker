import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { currentSession, readToken, signIn, signOut } from "./auth";
import { mockFetch, queueItem, testToken } from "./test/fakeApi";

const emptyQueue = {
  "/api/v1/clusters?": () => ({ body: [queueItem()] }),
  "/api/v1/takeovers?": () => ({ body: [] }),
  "/api/v1/mules?": () => ({ body: [] }),
  "/api/v1/merge-proposals?": () => ({ body: [] }),
};

afterEach(() => {
  vi.useRealTimers();
});

describe("readToken", () => {
  const now = Date.parse("2026-11-17T10:00:00Z");
  const at = (iso: string) => Math.floor(Date.parse(iso) / 1000);

  it("reads the name, roles and expiry without checking the signature", () => {
    const session = readToken(
      `  ${testToken({ sub: "u-1", preferred_username: "Zoë", roles: ["Analyst", 4], exp: at("2026-11-17T11:00:00Z") })}\n`,
      now,
    );

    expect(session).toEqual({
      token: expect.not.stringMatching(/\s/),
      name: "Zoë",
      roles: ["analyst"],
      expiresAt: Date.parse("2026-11-17T11:00:00Z"),
    });
  });

  it("falls back to the subject and allows a token with no roles", () => {
    expect(readToken(testToken({ sub: "u-2", roles: undefined, exp: at("2026-11-17T11:00:00Z") }), now)).toMatchObject({
      name: "u-2",
      roles: [],
    });
  });

  it.each([
    ["not three parts", "abc.def", /three parts/],
    ["unreadable", "a.!!!.c", /can't be read/],
    ["nobody named", testToken({ sub: "", exp: 9_999_999_999 }), /names nobody/],
    ["no expiry", testToken({ exp: undefined }), /no expiry/],
    ["expired", testToken({ exp: at("2026-11-17T09:59:59Z") }), /expired/],
  ])("refuses a token that is %s", (_, token, message) => {
    const result = readToken(token, now);
    expect("error" in result && result.error).toMatch(message);
  });
});

describe("sign-in", () => {
  it("asks for a token before showing anything, and loads nothing until signed in", async () => {
    signOut();
    const { calls } = mockFetch(emptyQueue);

    render(<App />);

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
    expect(calls).toHaveLength(0);
  });

  it("explains a bad token and keeps the form", async () => {
    signOut();
    mockFetch(emptyQueue);
    render(<App />);

    await userEvent.type(screen.getByLabelText("Token"), "not-a-token");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(screen.getByRole("alert")).toHaveTextContent("That isn't a token");
    expect(currentSession()).toBeNull();
  });

  it("signs in with a pasted token, shows who is signed in, and sends the token with every call", async () => {
    signOut();
    const { calls } = mockFetch(emptyQueue);
    render(<App />);
    const token = testToken({ sub: "u-7", preferred_username: "alice", roles: ["analyst", "auditor"] });

    await userEvent.click(screen.getByLabelText("Token"));
    await userEvent.paste(token);
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("table", { name: "Clusters waiting for review" })).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("(Analyst, Auditor)")).toBeInTheDocument();
    expect(calls.length).toBeGreaterThan(0);
    expect(calls.every((call) => call.authorization === `Bearer ${token}`)).toBe(true);
  });

  it("signs out and forgets the token", async () => {
    mockFetch(emptyQueue);
    render(<App />);
    await screen.findByRole("table", { name: "Clusters waiting for review" });

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(sessionStorage.getItem("linker.token")).toBeNull();
  });

  it("goes back to sign-in, saying why, when the service refuses the token", async () => {
    mockFetch({
      ...emptyQueue,
      "/api/v1/clusters?": () => ({ status: 401, body: { title: "Unauthorized", detail: "A valid bearer token is required." } }),
    });

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Your session has ended. Sign in again.");
    expect(currentSession()).toBeNull();
  });

  it("stays signed in and says so plainly when the role can't do something", async () => {
    mockFetch({
      ...emptyQueue,
      "/api/v1/clusters?": () => ({ status: 403, body: { title: "Forbidden", detail: "Your role can't do this." } }),
    });

    render(<App />);

    await waitFor(() => expect(screen.getAllByRole("alert")[0]).toHaveTextContent("Your role can't do this."));
    expect(screen.queryByRole("heading", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("signs out when the token expires", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    signOut();
    mockFetch(emptyQueue);
    signIn(testToken({ exp: Math.floor(Date.now() / 1000) + 60 }));
    render(<App />);
    expect(screen.queryByRole("heading", { name: "Sign in" })).not.toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(61_000);
    });

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Your session has ended.");
  });
});
