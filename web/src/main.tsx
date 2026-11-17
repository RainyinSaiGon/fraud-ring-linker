import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { completeSignIn } from "./oidc";
import "./styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html is missing #root");

// Back from the identity provider? Finish signing in before the first render.
void completeSignIn().then((signInError) =>
  createRoot(root).render(
    <StrictMode>
      <App signInError={signInError} />
    </StrictMode>,
  ),
);
