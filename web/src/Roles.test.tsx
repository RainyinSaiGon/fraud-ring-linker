import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "./App";
import { signIn, signOut } from "./auth";
import { accountDetail, clusterDetail, mockFetch, proposal, PROPOSAL_ID, testToken } from "./test/fakeApi";

const routes = {
  "/api/v1/clusters/c7/decisions": () => ({ body: [] }),
  "/api/v1/clusters/c7": () => ({ body: clusterDetail() }),
  "/api/v1/audit-events": () => ({ body: [] }),
  "/api/v1/accounts/acc_1/logins": () => ({ body: [] }),
  "/api/v1/accounts/acc_1/takeover-decisions": () => ({ body: [] }),
  "/api/v1/accounts/acc_1/transfers": () => ({ body: [] }),
  "/api/v1/accounts/acc_1": () => ({ body: accountDetail() }),
  [`/api/v1/merge-proposals/${PROPOSAL_ID}`]: () => ({ body: proposal() }),
};

describe("roles on screen", () => {
  it.each(["#/clusters/c7", "#/accounts/acc_1", `#/proposals/${PROPOSAL_ID}`])(
    "hides the decision form from an auditor on %s",
    async (hash) => {
      signOut();
      signIn(testToken({ sub: "aud", roles: ["auditor"] }));
      mockFetch(routes);
      window.location.hash = hash;
      render(<App />);

      expect(await screen.findByText("Only analysts can decide. You can read everything here.")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /Confirm|Merge clusters|Not a takeover|Show details/ })).toBeNull();
    },
  );

  it("shows it to an analyst", async () => {
    mockFetch(routes);
    window.location.hash = "#/clusters/c7";
    render(<App />);

    expect(await screen.findByRole("button", { name: "Confirm fraud" })).toBeEnabled();
  });
});
