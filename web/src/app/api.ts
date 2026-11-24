export class ApiError extends Error {
  constructor(public status: number, public code: string | null) { super(`HTTP ${status} ${code ?? ""}`); }
}

export const API_BASE: string = (import.meta.env.PUBLIC_API_URL as string | undefined) || (typeof location !== "undefined" ? location.origin : "");

export class Api {
  constructor(public base: string, private token: () => string | null) {}

  async call<T = any>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}, auth = true): Promise<T> {
    const h: Record<string, string> = { accept: "application/json", ...headers };
    const t = this.token();
    if (auth && t) h.authorization = `Bearer ${t}`;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(this.base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(res.status, json?.error ?? null);
    return json as T;
  }
}
