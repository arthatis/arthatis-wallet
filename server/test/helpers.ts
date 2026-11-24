import { createApp } from "../src/app.ts";
import { openDb } from "../src/db.ts";
import { defaultPolicy, type AppDeps } from "../src/context.ts";

export type Mail = { to: string; subject: string; html: string };

export function makeApp(overrides: Omit<Partial<AppDeps>, "policy"> & { policy?: Partial<AppDeps["policy"]> } = {}) {
  const clock = { t: 1_000_000 };
  const mails: Mail[] = [];
  const deps: AppDeps = {
    db: openDb(":memory:"),
    now: () => clock.t,
    mailer: async (m) => {
      mails.push(m);
    },
    appOrigin: "https://wallet.test",
    corsOrigin: "*",
    ai: null,
    ...overrides,
    policy: { ...defaultPolicy, ...(overrides.policy ?? {}) },
  };
  const app = createApp(deps);
  const call = async (method: string, path: string, opts: { token?: string; body?: unknown; headers?: Record<string, string> } = {}) => {
    const headers: Record<string, string> = { ...(opts.headers ?? {}) };
    if (opts.token) headers.authorization = `Bearer ${opts.token}`;
    if (opts.body !== undefined) headers["content-type"] = "application/json";
    const res = await app.request(path, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    });
    return { status: res.status, json: (await res.json().catch(() => null)) as any };
  };
  const register = async (label = "Pixel") => {
    const r = await call("POST", "/v1/devices", {
      body: { label, signPub: "sign-pub-key", boxPub: "box-pub-key", kemPub: "kem-pub-key" },
    });
    return r.json as { vaultId: string; deviceId: string; token: string };
  };
  return { app, deps, clock, mails, call, register };
}

export const rec = (id: string, updatedAt: number, deletedAt: number | null = null, payload = "Y2lwaGVy") => ({
  entity: "transaction",
  id,
  updatedAt,
  deletedAt,
  payload,
});
