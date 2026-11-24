import { serve } from "@hono/node-server";
import { serveStatic } from "@hono/node-server/serve-static";
import { createApp } from "./app.ts";
import type { AppDeps } from "./context.ts";
import { purgeUnactivated } from "./purge.ts";
import { openDb } from "./db.ts";
import { createMailer } from "./mail.ts";

const env = process.env;

const deps: AppDeps = {
  db: openDb(`${env.DATA_DIR ?? "./data"}/arthatis.sqlite`),
  now: () => Date.now(),
  mailer: createMailer(env),
  contactTo: env.CONTACT_TO,
  appOrigin: env.APP_ORIGIN ?? "https://wallet.arthatis.com",
  corsOrigin: env.CORS_ORIGIN ?? "*",
  policy: {
    registrationDefault: env.REGISTRATION_MODE === "email" ? "email" : "open",
    requireActivation: env.REQUIRE_ACTIVATION === "true",
    payUrl: env.PAY_URL || undefined,
    adminToken: env.ADMIN_API_TOKEN || undefined,
    pendingPurgeDays: Number(env.PENDING_PURGE_DAYS ?? 7),
    registerRatePerHour: Number(env.REGISTER_RATE_PER_HOUR ?? 20),
    trustProxy: env.TRUST_PROXY === "true",
  },
  ai: env.OLLAMA_URL
    ? { url: env.OLLAMA_URL, model: env.OLLAMA_MODEL ?? "llama3.2", apiKey: env.OLLAMA_API_KEY }
    : null,
};

const app = createApp(deps);

// Optional: serve the built web mirror (arthatis-wallet/web/dist) from the same origin, so self-hosting is one container.
// Unknown paths (e.g. /invite/<token>) fall back to index.html; the web app routes them client-side.
if (env.WEB_DIR) {
  const root = env.WEB_DIR;
  app.use("/*", serveStatic({ root }));
  app.get("*", serveStatic({ root, rewriteRequestPath: () => "/index.html" }));
}

// Hosted servers: drop vaults that were never activated.
if (deps.policy.requireActivation) {
  const sweep = () => { try { const n = purgeUnactivated(deps.db, Date.now(), deps.policy.pendingPurgeDays); if (n) console.log(`purged ${n} unactivated vaults`); } catch (e) { console.error(e); } };
  sweep();
  setInterval(sweep, 3_600_000).unref();
}

const port = Number(env.PORT ?? 8787);
serve({ fetch: app.fetch, port }, (info) => {
  console.log(`arthatis wallet server listening on :${info.port}`);
});
