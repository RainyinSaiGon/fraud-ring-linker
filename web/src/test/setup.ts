import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";
import { signIn, signOut } from "../auth";
import { testToken } from "./fakeApi";

// Pages render for a signed-in analyst unless a test signs out first.
beforeEach(() => {
  signIn(testToken());
});

afterEach(() => {
  cleanup();
  signOut();
  window.location.hash = "";
});
