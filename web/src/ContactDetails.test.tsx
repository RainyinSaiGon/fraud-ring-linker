import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "./App";
import { signIn, signOut } from "./auth";
import { describeEvent } from "./labels";
import { accountDetail, mockFetch, testToken } from "./test/fakeApi";

const revealed = { account_id: "acc_1", email: "acc.1@example.com", ip: "10.0.0.1" };

function routes(reveal: () => { status?: number; body?: unknown } = () => ({ body: revealed })) {
  let revealedYet = false;
  return {
    "/api/v1/accounts/acc_1/reveal": () => {
      revealedYet = true;
      return reveal();
    },
    "/api/v1/accounts/acc_1/logins": () => ({ body: [] }),
    "/api/v1/accounts/acc_1/takeover-decisions": () => ({ body: [] }),
    "/api/v1/accounts/acc_1": () => ({ body: accountDetail() }),
    "/api/v1/audit-events": () => ({
      body: revealedYet
        ? [{ event_id: 9, occurred_at: "2026-11-17T10:00:00Z", event_type: "pii.revealed", account_id: "acc_1",
             cluster_id: "c7", actor: "ana", details: { fields: ["email", "ip"], reason: "Customer called" } }]
        : [],
    }),
  };
}

async function openAccount() {
  window.location.hash = "#/accounts/acc_1";
  render(<App />);
  return screen.findByRole("heading", { name: "Contact details" });
}

describe("contact details", () => {
  it("shows the email and IP masked", async () => {
    mockFetch(routes());
    await openAccount();

    expect(screen.getByTestId("contact-email")).toHaveTextContent("a***@example.com");
    expect(screen.getByTestId("contact-ip")).toHaveTextContent("10.0.0.x");
    expect(screen.getByText(/Showing the full details is recorded/)).toBeInTheDocument();
  });

  it("lets an analyst show the full details with a reason, and the history records it", async () => {
    const { calls } = mockFetch(routes());
    await openAccount();

    await userEvent.type(screen.getByLabelText("Reason (optional)"), " Customer called ");
    await userEvent.click(screen.getByRole("button", { name: "Show details" }));

    expect(await screen.findByText("Shown to you and recorded in the history.")).toBeInTheDocument();
    expect(screen.getByTestId("contact-email")).toHaveTextContent("acc.1@example.com");
    expect(screen.getByTestId("contact-ip")).toHaveTextContent("10.0.0.1");
    const post = calls.find((call) => call.method === "POST")!;
    expect(post.url).toBe("/api/v1/accounts/acc_1/reveal");
    expect(post.body).toEqual({ reason: "Customer called" });
    const history = screen.getByRole("heading", { name: "History" }).closest(".card") as HTMLElement;
    await waitFor(() =>
      expect(within(history).getByText("ana showed acc_1's contact details: Customer called")).toBeInTheDocument(),
    );
  });

  it("sends no reason as null", async () => {
    const { calls } = mockFetch(routes());
    await openAccount();

    await userEvent.click(screen.getByRole("button", { name: "Show details" }));

    await waitFor(() => expect(calls.find((call) => call.method === "POST")?.body).toEqual({ reason: null }));
  });

  it("offers an auditor no way to show the details", async () => {
    signOut();
    signIn(testToken({ sub: "aud", roles: ["auditor"] }));
    mockFetch(routes());
    await openAccount();

    expect(screen.queryByRole("button", { name: "Show details" })).not.toBeInTheDocument();
    expect(screen.getByText("Masked. Only analysts can show the full details.")).toBeInTheDocument();
  });

  it("keeps the details masked and says why when the reveal fails", async () => {
    mockFetch(routes(() => ({ status: 403, body: { title: "Forbidden", detail: "Your role can't do this." } })));
    await openAccount();

    await userEvent.click(screen.getByRole("button", { name: "Show details" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Your role can't do this.");
    expect(screen.getByTestId("contact-email")).toHaveTextContent("a***@example.com");
    expect(screen.getByRole("button", { name: "Show details" })).toBeEnabled();
  });

  it("words a reveal with no reason", () => {
    expect(describeEvent({ event_id: 1, occurred_at: "", event_type: "pii.revealed", account_id: "a1",
      cluster_id: null, actor: "bo", details: { fields: ["email", "ip"] } })).toBe("bo showed a1's contact details");
  });
});
