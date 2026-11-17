// Checks the production build: the paste-a-token sign-in must not be in it, and
// sign-in through the identity provider must be. Run after `npm run build`.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const dist = new URL("../dist/", import.meta.url);
const files = readdirSync(new URL("assets/", dist)).filter((f) => f.endsWith(".js"));
if (files.length === 0) throw new Error("No built scripts in dist/assets. Run `npm run build` first.");
const code = files.map((f) => readFileSync(join(new URL("assets/", dist).pathname.replace(/^\/([A-Za-z]:)/, "$1"), f), "utf8")).join("\n");

const forbidden = ["Development: paste a token", "sign-in-token", "linker.token"];
const required = ["Sign in through your identity provider"];
const problems = [
  ...forbidden.filter((text) => code.includes(text)).map((text) => `the production build contains "${text}"`),
  ...required.filter((text) => !code.includes(text)).map((text) => `the production build is missing "${text}"`),
];
if (problems.length > 0) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log(`Production build checked (${files.length} script file(s)): no paste-a-token sign-in.`);
