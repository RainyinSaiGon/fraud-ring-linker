import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";
import { App, parseRoute } from "./App";
import { ruleLabel, signalLabel } from "./labels";
import {
  clusterDetail,
  decisionResult,
  mergeAcceptResult,
  mockFetch,
  proposal,
  PROPOSAL_ID,
  queueItem,
} from "./test/fakeApi";

describe("parseRoute", () => {
  it.each([
    ["", { page: "queue" }],
    ["#/", { page: "queue" }],
    ["#/clusters/c12", { page: "cluster", clusterId: "c12" }],
    ["#/clusters/../etc", { page: "queue" }],
    ["#/clusters/", { page: "queue" }],
    [`#/proposals/${PROPOSAL_ID}`, { page: "proposal", proposalId: PROPOSAL_ID }],
    ["#/proposals/not-a-uuid", { page: "queue" }],
  ])("%s", (hash, expected) => {
    expect(parseRoute(hash)).toEqual(expected);
  });
});

describe("review queue", () => {
  it("lists clusters waiting for review with links to each one", async () => {
    mockFetch({
      "/api/v1/clusters?": () => ({ body: [queueItem(), queueItem({ cluster_id: "c9", size: 2 })] }),
      "/api/v1/merge-proposals?": () => ({ body: [] }),
    });

    render(<App />);

    const table = await screen.findByRole("table", { name: "Clusters waiting for review" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]!).getByRole("link", { name: "c7" })).toHaveAttribute("href", "#/clusters/c7");
    expect(within(rows[1]!).getByText("89%")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Needs review")).toBeInTheDocument();
  });

  it("says so when there is nothing to review", async () => {
    mockFetch({ "/api/v1/clusters?": () => ({ body: [] }), "/api/v1/merge-proposals?": () => ({ body: [] }) });
    render(<App />);
    expect(await screen.findByText(/Nothing to review/)).toBeInTheDocument();
    expect(await screen.findByText("No merge proposals waiting.")).toBeInTheDocument();
  });

  it("shows the service's message when it is unavailable, and recovers on refresh", async () => {
    let up = false;
    mockFetch({
      "/api/v1/clusters?": () =>
        up ? { body: [queueItem()] } : { status: 503, body: { title: "Service unavailable", detail: "Linking is temporarily unavailable. Retry later." } },
      "/api/v1/merge-proposals?": () => ({ body: [] }),
    });
    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Linking is temporarily unavailable");
    up = true;
    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await screen.findByRole("link", { name: "c7" })).toBeInTheDocument();
  });
});

describe("cluster page", () => {
  function routesFor(cluster = clusterDetail()) {
    return {
      "/api/v1/clusters/c7/decisions": (init?: RequestInit) =>
        init?.method === "POST" ? { status: 201, body: decisionResult("Ana") } : { body: [] },
      "/api/v1/clusters/c7": () => ({ body: cluster }),
      "/api/v1/audit-events": () => ({ body: [] }),
    };
  }

  it("explains why the accounts are linked in plain language", async () => {
    mockFetch(routesFor());
    window.location.hash = "#/clusters/c7";

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Cluster c7" })).toBeInTheDocument();
    expect(screen.getByText("Same payment card, rarely shared")).toBeInTheDocument();
    expect(screen.getByText("same card")).toBeInTheDocument();
    expect(screen.getByTestId("reason")).toHaveTextContent("not reviewed yet");
  });

  it("keeps both decision buttons disabled until the analyst gives a name", async () => {
    const { calls } = mockFetch(routesFor());
    window.location.hash = "#/clusters/c7";
    render(<App />);
    const confirm = await screen.findByRole("button", { name: "Confirm fraud" });

    expect(confirm).toBeDisabled();
    expect(screen.getByRole("button", { name: "Not fraud" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Your name"), "   ");
    expect(confirm).toBeDisabled();
    expect(calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("sends the decision with the analyst's name and note, then reloads the cluster", async () => {
    const { calls } = mockFetch(routesFor());
    window.location.hash = "#/clusters/c7";
    render(<App />);

    await userEvent.type(await screen.findByLabelText("Your name"), " Ana ");
    await userEvent.type(screen.getByLabelText("Note (optional)"), "Same card on all three");
    await userEvent.click(screen.getByRole("button", { name: "Confirm fraud" }));

    await waitFor(() => expect(calls.filter((call) => call.method === "POST")).toHaveLength(1));
    const post = calls.find((call) => call.method === "POST")!;
    expect(post.url).toBe("/api/v1/clusters/c7/decisions");
    expect(post.body).toEqual({ outcome: "CONFIRMED_FRAUD", analyst: "Ana", note: "Same card on all three" });
    await waitFor(() => expect(calls.filter((call) => call.url === "/api/v1/clusters/c7")).toHaveLength(2));
  });

  it("shows why a decision failed and keeps the form usable", async () => {
    mockFetch({
      ...routesFor(),
      "/api/v1/clusters/c7/decisions": (init?: RequestInit) =>
        init?.method === "POST" ? { status: 404, body: { detail: "Cluster c7 not found." } } : { body: [] },
    });
    window.location.hash = "#/clusters/c7";
    render(<App />);

    await userEvent.type(await screen.findByLabelText("Your name"), "Ana");
    await userEvent.click(screen.getByRole("button", { name: "Not fraud" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Cluster c7 not found.");
    expect(screen.getByRole("button", { name: "Not fraud" })).toBeEnabled();
  });

  it("describes proposed and accepted merges in the history", async () => {
    const event = (event_id: number, event_type: string, actor: string, details: Record<string, unknown>) => ({
      event_id,
      occurred_at: "2026-11-14T03:00:00Z",
      event_type,
      account_id: event_type === "merge.proposed" ? "acc_3" : null,
      cluster_id: "c7",
      actor,
      details,
    });
    mockFetch({
      ...routesFor(),
      "/api/v1/audit-events": () => ({
        body: [
          event(2, "merge.accepted", "Ana", { cluster_size: 3 }),
          event(1, "merge.proposed", "system", { other_cluster_id: "c9" }),
        ],
      }),
    });
    window.location.hash = "#/clusters/c7";
    render(<App />);

    expect(await screen.findByText("Ana merged clusters (3 accounts)")).toBeInTheDocument();
    expect(screen.getByText("acc_3 also links to cluster c9; merge proposed")).toBeInTheDocument();
  });

  it("reports a cluster that does not exist", async () => {
    mockFetch({});
    window.location.hash = "#/clusters/c999999";
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Cluster c999999 not found.");
    expect(screen.getByRole("link", { name: /Back to the review queue/ })).toHaveAttribute("href", "#/");
  });
});

describe("merge proposals in the queue", () => {
  it("lists open proposals with the evidence and a link to review each one", async () => {
    const { calls } = mockFetch({
      "/api/v1/clusters?": () => ({ body: [] }),
      "/api/v1/merge-proposals?": () => ({ body: [proposal()] }),
    });

    render(<App />);

    const table = await screen.findByRole("table", { name: "Merge proposals waiting for a decision" });
    const row = within(table).getAllByRole("row")[1]!;
    expect(row).toHaveTextContent("c4 ↔ c5");
    expect(row).toHaveTextContent("Same phone, similar email (90%)");
    expect(within(row).getByText("3")).toBeInTheDocument();
    expect(within(row).getByRole("link", { name: "Review merge of c4 and c5" })).toHaveAttribute(
      "href",
      `#/proposals/${PROPOSAL_ID}`,
    );
    expect(calls.some((call) => call.url === "/api/v1/merge-proposals?status=OPEN&limit=100")).toBe(true);
  });

  it("keeps the cluster queue usable when proposals fail to load, and refreshes both", async () => {
    let up = false;
    mockFetch({
      "/api/v1/clusters?": () => ({ body: [queueItem()] }),
      "/api/v1/merge-proposals?": () =>
        up ? { body: [proposal()] } : { status: 503, body: { detail: "Linking is temporarily unavailable. Retry later." } },
    });
    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Merge proposals could not be loaded: Linking is temporarily unavailable",
    );
    expect(screen.getByRole("link", { name: "c7" })).toBeInTheDocument();
    up = true;
    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await screen.findByRole("link", { name: "Review merge of c4 and c5" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("proposal page", () => {
  const url = `/api/v1/merge-proposals/${PROPOSAL_ID}`;

  function routesFor(current = proposal()) {
    return {
      [`${url}/accept`]: () => ({ body: mergeAcceptResult("Ana") }),
      [`${url}/reject`]: () => ({
        body: proposal({ status: "REJECTED", decided_by: "Ana", decided_at: "2026-11-14T03:00:00Z", note: "Different people" }),
      }),
      [url]: () => ({ body: current }),
    };
  }

  function open() {
    window.location.hash = `#/proposals/${PROPOSAL_ID}`;
    render(<App />);
  }

  it("shows both sides and the evidence that joins them", async () => {
    mockFetch(routesFor());
    open();

    expect(await screen.findByRole("heading", { name: "Merge c4 and c5?" })).toBeInTheDocument();
    expect(screen.getByTestId("evidence")).toHaveTextContent(
      "acc_3 joined c4 and also links to c5: Same phone, similar email (90%)",
    );
    const sideA = screen.getByRole("list", { name: "Side A: cluster c4 accounts" });
    expect(within(sideA).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "acc_1",
      "acc_3 (new account)",
    ]);
    const sideB = screen.getByRole("list", { name: "Side B: cluster c5 accounts" });
    expect(within(sideB).getByText("acc_2")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See why cluster c5 is linked" })).toHaveAttribute("href", "#/clusters/c5");
  });

  it("keeps both buttons disabled until the analyst gives a name", async () => {
    const { calls } = mockFetch(routesFor());
    open();
    const merge = await screen.findByRole("button", { name: "Merge clusters" });

    expect(merge).toBeDisabled();
    expect(screen.getByRole("button", { name: "Keep apart" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Your name"), "  ");
    expect(merge).toBeDisabled();
    expect(calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("merges with the analyst's name and note, then shows the merged cluster", async () => {
    const { calls } = mockFetch(routesFor());
    open();

    await userEvent.type(await screen.findByLabelText("Your name"), " Ana ");
    await userEvent.type(screen.getByLabelText("Note (optional)"), "Same phone");
    await userEvent.click(screen.getByRole("button", { name: "Merge clusters" }));

    const result = await screen.findByTestId("merge-result");
    expect(result).toHaveTextContent("Clusters merged");
    expect(result).toHaveTextContent("Cluster c4 now has 3 accounts.");
    expect(within(result).getByRole("link", { name: "c4" })).toHaveAttribute("href", "#/clusters/c4");
    expect(within(result).getByText("Needs review")).toBeInTheDocument();
    expect(screen.getByText(/Merged by Ana/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Merge clusters" })).not.toBeInTheDocument();

    const post = calls.find((call) => call.method === "POST")!;
    expect(post.url).toBe(`${url}/accept`);
    expect(post.body).toEqual({ analyst: "Ana", note: "Same phone" });
  });

  it("keeps the clusters apart on reject", async () => {
    const { calls } = mockFetch(routesFor());
    open();

    await userEvent.type(await screen.findByLabelText("Your name"), "Ana");
    await userEvent.click(screen.getByRole("button", { name: "Keep apart" }));

    expect(await screen.findByText(/Kept apart by Ana/)).toBeInTheDocument();
    expect(screen.getByText("This pair will not be proposed again.")).toBeInTheDocument();
    expect(screen.getByText("“Different people”")).toBeInTheDocument();
    const post = calls.find((call) => call.method === "POST")!;
    expect(post.url).toBe(`${url}/reject`);
    expect(post.body).toEqual({ analyst: "Ana", note: null });
  });

  it("shows why a merge was refused and keeps the form usable", async () => {
    mockFetch({
      ...routesFor(),
      [`${url}/accept`]: () => ({
        status: 409,
        body: {
          detail: "Merging these clusters would link accounts verified as different people. Reject the proposal instead.",
        },
      }),
    });
    open();

    await userEvent.type(await screen.findByLabelText("Your name"), "Ana");
    await userEvent.click(screen.getByRole("button", { name: "Merge clusters" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("verified as different people");
    expect(screen.getByRole("button", { name: "Merge clusters" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Keep apart" })).toBeEnabled();
  });

  it("shows a decided proposal without the form", async () => {
    mockFetch(routesFor(mergeAcceptResult("Ben").proposal));
    open();

    expect(await screen.findByText(/Merged by Ben/)).toBeInTheDocument();
    expect(screen.getByText("Merged", { selector: ".badge" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Your name")).not.toBeInTheDocument();
  });

  it("says when both sides are already in one cluster", async () => {
    mockFetch(routesFor(proposal({ current_cluster_b: "c4" })));
    open();
    expect(await screen.findByText(/already in one cluster/)).toBeInTheDocument();
  });

  it("reports a proposal that does not exist", async () => {
    mockFetch({});
    open();
    expect(await screen.findByRole("alert")).toHaveTextContent("Merge proposal not found.");
  });
});

describe("api client", () => {
  it("explains a network failure in plain words", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }));
    await expect(api.reviewQueue()).rejects.toEqual(new ApiError(0, "Can't reach the linking service. Check that it is running."));
  });

  it("falls back to a status message when the error body is not JSON", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("oops", { status: 500 })));
    await expect(api.cluster("c1")).rejects.toHaveProperty("message", "The service answered with status 500.");
  });

  it("sends an empty note as null", async () => {
    const { calls } = mockFetch({ "/api/v1/clusters/c1/decisions": () => ({ status: 201, body: decisionResult("x") }) });
    await api.decide("c1", "NOT_FRAUD", "x", "   ");
    expect(calls[0]!.body).toEqual({ outcome: "NOT_FRAUD", analyst: "x", note: null });
  });
});

describe("labels", () => {
  it("has a plain name for known codes and a readable fallback for new ones", () => {
    expect(ruleLabel("device_and_email")).toBe("Same phone, similar email");
    expect(ruleLabel("brand_new_rule")).toBe("brand new rule");
    expect(signalLabel("same_device")).toBe("same phone");
  });
});
