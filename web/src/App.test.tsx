import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";
import { App, parseRoute } from "./App";
import { ruleLabel, signalLabel } from "./labels";
import { clusterDetail, decisionResult, mockFetch, queueItem } from "./test/fakeApi";

describe("parseRoute", () => {
  it.each([
    ["", { page: "queue" }],
    ["#/", { page: "queue" }],
    ["#/clusters/c12", { page: "cluster", clusterId: "c12" }],
    ["#/clusters/../etc", { page: "queue" }],
    ["#/clusters/", { page: "queue" }],
  ])("%s", (hash, expected) => {
    expect(parseRoute(hash)).toEqual(expected);
  });
});

describe("review queue", () => {
  it("lists clusters waiting for review with links to each one", async () => {
    mockFetch({ "/api/v1/clusters?": () => ({ body: [queueItem(), queueItem({ cluster_id: "c9", size: 2 })] }) });

    render(<App />);

    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]!).getByRole("link", { name: "c7" })).toHaveAttribute("href", "#/clusters/c7");
    expect(within(rows[1]!).getByText("89%")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Needs review")).toBeInTheDocument();
  });

  it("says so when there is nothing to review", async () => {
    mockFetch({ "/api/v1/clusters?": () => ({ body: [] }) });
    render(<App />);
    expect(await screen.findByText(/Nothing to review/)).toBeInTheDocument();
  });

  it("shows the service's message when it is unavailable, and recovers on refresh", async () => {
    let up = false;
    mockFetch({
      "/api/v1/clusters?": () =>
        up ? { body: [queueItem()] } : { status: 503, body: { title: "Service unavailable", detail: "Linking is temporarily unavailable. Retry later." } },
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

  it("reports a cluster that does not exist", async () => {
    mockFetch({});
    window.location.hash = "#/clusters/c999999";
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Cluster c999999 not found.");
    expect(screen.getByRole("link", { name: /Back to the review queue/ })).toHaveAttribute("href", "#/");
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
