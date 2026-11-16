import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App, parseRoute } from "./App";
import { describeEvent, place, takeoverMark } from "./labels";
import {
  accountDetail,
  clusterDetail,
  loginItem,
  mockFetch,
  NEW_DEVICE_AT,
  queueItem,
  takeoverDecisionResult,
  takeoverSummary,
} from "./test/fakeApi";

const emptyQueue = {
  "/api/v1/clusters?": () => ({ body: [queueItem()] }),
  "/api/v1/mules?": () => ({ body: [] }),
  "/api/v1/merge-proposals?": () => ({ body: [] }),
};

describe("account routes", () => {
  it.each([
    ["#/accounts/acc_1", { page: "account", accountId: "acc_1" }],
    ["#/accounts/s3_00011.x-y", { page: "account", accountId: "s3_00011.x-y" }],
    ["#/accounts/org%3Aacc", { page: "account", accountId: "org:acc" }],
    ["#/accounts/", { page: "queue" }],
    ["#/accounts/a%2Fb", { page: "queue" }],
    ["#/accounts/a b", { page: "queue" }],
  ])("%s", (hash, expected) => {
    expect(parseRoute(hash)).toEqual(expected);
  });
});

describe("possible takeovers in the queue", () => {
  it("lists flagged accounts with their signals and a link to each account", async () => {
    mockFetch({ ...emptyQueue, "/api/v1/takeovers?": () => ({ body: [takeoverSummary()] }) });
    window.location.hash = "#/";
    render(<App />);

    const table = await screen.findByRole("table", { name: "Possible takeovers waiting for review" });
    const row = within(table).getByRole("row", { name: /acc_1/ });
    expect(row).toHaveTextContent("82%");
    expect(row).toHaveTextContent("first login from a new device after 7 logins, 16,990 km in 45 min");
    expect(within(row).getByRole("link", { name: "Review account acc_1" })).toHaveAttribute("href", "#/accounts/acc_1");
  });

  it("says so when there are none", async () => {
    mockFetch({ ...emptyQueue, "/api/v1/takeovers?": () => ({ body: [] }) });
    window.location.hash = "#/";
    render(<App />);
    expect(await screen.findByText("No possible takeovers waiting.")).toBeInTheDocument();
  });

  it("keeps the cluster queue usable when takeovers fail to load", async () => {
    mockFetch({ ...emptyQueue, "/api/v1/takeovers?": () => ({ status: 503, body: { detail: "Try later." } }) });
    window.location.hash = "#/";
    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Possible takeovers could not be loaded: Try later.");
    expect(await screen.findByRole("link", { name: "c7" })).toBeInTheDocument();
  });

  it("shows when a flag has no recorded time yet", async () => {
    mockFetch({ ...emptyQueue, "/api/v1/takeovers?": () => ({ body: [takeoverSummary({ flagged_at: null })] }) });
    window.location.hash = "#/";
    render(<App />);
    const table = await screen.findByRole("table", { name: "Possible takeovers waiting for review" });
    expect(within(table).getByText("Just now")).toBeInTheDocument();
  });
});

describe("account page", () => {
  const logins = [
    loginItem({ login_id: "l3", device_id: "dev_attacker", occurred_at: NEW_DEVICE_AT, latitude: 51.51, longitude: -0.13 }),
    loginItem({ login_id: "l2" }),
    loginItem({ login_id: "l1", success: false, occurred_at: "2026-11-13T08:00:00Z", latitude: null, longitude: null, device_id: null }),
  ];

  function routesFor(account = accountDetail(), audit: unknown[] = []) {
    return {
      "/api/v1/accounts/acc_1/logins": () => ({ body: logins }),
      "/api/v1/accounts/acc_1/takeover-decisions": (init?: RequestInit) =>
        init?.method === "POST"
          ? { status: 201, body: takeoverDecisionResult("Ana", JSON.parse(String(init.body)).outcome) }
          : { body: [] },
      "/api/v1/accounts/acc_1": () => ({ body: account }),
      "/api/v1/audit-events": () => ({ body: audit }),
    };
  }

  it("shows the account's action, takeover score and signals in words", async () => {
    mockFetch(routesFor());
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Account acc_1" })).toBeInTheDocument();
    expect(screen.getByText("Needs review")).toBeInTheDocument();
    expect(screen.getByTestId("reason")).toHaveTextContent("may have been taken over");
    expect(screen.getByText("Possible takeover")).toBeInTheDocument();
    const signals = screen.getByRole("list", { name: "Takeover signals" });
    expect(within(signals).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "first login from a new device after 7 logins",
      "16,990 km in 45 min (22,653 km/h)",
    ]);
    expect(screen.getByRole("link", { name: "c7" })).toHaveAttribute("href", "#/clusters/c7");
    expect(screen.getByText("(2 accounts)")).toBeInTheDocument();
  });

  it("marks the login that showed each signal in words, with device, place and result", async () => {
    mockFetch(routesFor());
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    const table = await screen.findByRole("table", { name: "Recent logins of acc_1" });
    const [, attacker, own, failed] = within(table).getAllByRole("row");
    expect(attacker).toHaveTextContent("dev_attacker");
    expect(attacker).toHaveTextContent("51.51, -0.13");
    expect(attacker).toHaveTextContent("New device");
    expect(attacker).toHaveTextContent("Impossible jump: 16,990 km in 45 min (22,653 km/h)");
    expect(own).toHaveTextContent("Success");
    expect(own).not.toHaveTextContent("New device");
    expect(failed).toHaveTextContent("Failed");
    expect(within(failed!).getAllByText("Unknown")).toHaveLength(2);
  });

  it("says when there are no logins or signals yet", async () => {
    const quiet = accountDetail({ takeover_score: 0, takeover_flagged: false, takeover_reasons: [], risk_action: "ALLOW", reason: "Not linked to any other account." });
    mockFetch({ ...routesFor(quiet), "/api/v1/accounts/acc_1/logins": () => ({ body: [] }) });
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    expect(await screen.findByText("No logins yet.")).toBeInTheDocument();
    expect(screen.getByText("No takeover signals.")).toBeInTheDocument();
    expect(screen.getByText("Not flagged")).toBeInTheDocument();
    expect(screen.getByText("Allow")).toBeInTheDocument();
  });

  it("explains that confirming steps up this account only, and needs a name", async () => {
    const { calls } = mockFetch(routesFor());
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    const confirm = await screen.findByRole("button", { name: "Confirm takeover" });
    expect(screen.getByText(/steps up this account only/)).toHaveTextContent(
      "The 1 other account(s) in its cluster are not affected.",
    );
    expect(confirm).toBeDisabled();
    expect(screen.getByRole("button", { name: "Not a takeover" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Your name"), "   ");
    expect(confirm).toBeDisabled();
    expect(calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("sends the decision with the analyst's name and note, then reloads the account", async () => {
    let decided = false;
    const { calls } = mockFetch({
      ...routesFor(),
      "/api/v1/accounts/acc_1/takeover-decisions": (init?: RequestInit) => {
        if (init?.method !== "POST") return { body: [] };
        decided = true;
        return { status: 201, body: takeoverDecisionResult("Ana", "CONFIRMED_TAKEOVER") };
      },
      "/api/v1/accounts/acc_1": () => ({
        body: decided ? accountDetail({ risk_action: "STEP_UP", reason: "Re-verify the customer." }) : accountDetail(),
      }),
    });
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    await userEvent.type(await screen.findByLabelText("Your name"), " Ana ");
    await userEvent.type(screen.getByLabelText("Note (optional)"), "Customer did not log in from London");
    await userEvent.click(screen.getByRole("button", { name: "Confirm takeover" }));

    await waitFor(() => expect(calls.filter((call) => call.method === "POST")).toHaveLength(1));
    const post = calls.find((call) => call.method === "POST")!;
    expect(post.url).toBe("/api/v1/accounts/acc_1/takeover-decisions");
    expect(post.body).toEqual({
      outcome: "CONFIRMED_TAKEOVER",
      analyst: "Ana",
      note: "Customer did not log in from London",
    });
    expect(await screen.findByText("Step up")).toBeInTheDocument();
    expect(screen.getByTestId("reason")).toHaveTextContent("Re-verify the customer.");
  });

  it("sends Not a takeover with no note as null", async () => {
    const { calls } = mockFetch(routesFor());
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    await userEvent.type(await screen.findByLabelText("Your name"), "Ana");
    await userEvent.click(screen.getByRole("button", { name: "Not a takeover" }));

    await waitFor(() => expect(calls.find((call) => call.method === "POST")?.body).toEqual({
      outcome: "NOT_TAKEOVER",
      analyst: "Ana",
      note: null,
    }));
  });

  it("shows why a decision failed and keeps the form usable", async () => {
    mockFetch({
      ...routesFor(),
      "/api/v1/accounts/acc_1/takeover-decisions": (init?: RequestInit) =>
        init?.method === "POST" ? { status: 503, body: { detail: "Linking is temporarily unavailable." } } : { body: [] },
    });
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    await userEvent.type(await screen.findByLabelText("Your name"), "Ana");
    await userEvent.click(screen.getByRole("button", { name: "Confirm takeover" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Linking is temporarily unavailable.");
    expect(screen.getByRole("button", { name: "Confirm takeover" })).toBeEnabled();
  });

  it("describes takeover flags and decisions in the history", async () => {
    mockFetch(routesFor(accountDetail(), [
      { event_id: 2, occurred_at: "2026-11-14T11:00:00Z", event_type: "takeover.decided", account_id: "acc_1",
        cluster_id: "c7", actor: "Ana", details: { outcome: "CONFIRMED_TAKEOVER" } },
      { event_id: 1, occurred_at: "2026-11-14T10:46:00Z", event_type: "takeover.flagged", account_id: "acc_1",
        cluster_id: "c7", actor: "system", details: { description: "first login from a new device after 7 logins" } },
    ]));
    window.location.hash = "#/accounts/acc_1";
    render(<App />);

    expect(await screen.findByText("Ana marked acc_1 taken over")).toBeInTheDocument();
    expect(screen.getByText("acc_1 flagged as a possible takeover: first login from a new device after 7 logins"))
      .toBeInTheDocument();
  });

  it("reports an account that does not exist", async () => {
    mockFetch({});
    window.location.hash = "#/accounts/nobody";
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Account nobody not found.");
  });

  it("is reached from the cluster page's account list", async () => {
    mockFetch({
      "/api/v1/clusters/c7/decisions": () => ({ body: [] }),
      "/api/v1/clusters/c7": () => ({ body: clusterDetail() }),
      "/api/v1/audit-events": () => ({ body: [] }),
    });
    window.location.hash = "#/clusters/c7";
    render(<App />);
    expect(await screen.findByRole("link", { name: "Open account acc_2" })).toHaveAttribute("href", "#/accounts/acc_2");
  });
});

describe("takeover labels", () => {
  it("names each signal in words and places a login", () => {
    expect(takeoverMark("new_device")).toBe("New device");
    expect(takeoverMark("failed_burst_then_success")).toBe("Success after failed logins");
    expect(takeoverMark("something_new")).toBe("something new");
    expect(place(-33.8688, 151.2093)).toBe("-33.87, 151.21");
    expect(place(null, null)).toBe("Unknown");
    expect(describeEvent({ event_id: 1, occurred_at: "", event_type: "takeover.decided", account_id: "a",
      cluster_id: null, actor: "Ana", details: { outcome: "NOT_TAKEOVER" } })).toBe("Ana marked a not a takeover");
  });
});
