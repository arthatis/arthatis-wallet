import { Hono } from "hono";
import { zValidator } from "@hono/zod-validator";
import { requireAuth, type AppDeps, type HonoEnv } from "../context.ts";
import { aiChatSchema } from "../schemas.ts";

/**
 * Optional self-hosted LLM (Ollama or any OpenAI-compatible server).
 * This is the one place text leaves the phone unencrypted to the server, so the
 * app only calls it after the user opts in.
 */
export function aiRoutes(deps: AppDeps) {
  const app = new Hono<HonoEnv>();
  app.use("*", requireAuth(deps));

  app.get("/status", (c) => c.json({ available: deps.ai !== null, model: deps.ai?.model ?? null }));

  app.post("/chat", zValidator("json", aiChatSchema), async (c) => {
    if (!deps.ai) return c.json({ error: "ai_not_configured" }, 503);
    const { messages } = c.req.valid("json");
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (deps.ai.apiKey) headers.authorization = `Bearer ${deps.ai.apiKey}`;
    let response: Response;
    try {
      response = await fetch(`${deps.ai.url.replace(/\/$/, "")}/v1/chat/completions`, {
        method: "POST",
        headers,
        body: JSON.stringify({ model: deps.ai.model, messages, stream: false }),
        signal: AbortSignal.timeout(60_000),
      });
    } catch {
      return c.json({ error: "ai_unreachable" }, 502);
    }
    if (!response.ok) return c.json({ error: "ai_failed", status: response.status }, 502);
    const data = (await response.json()) as { choices?: Array<{ message?: { content?: string } }> };
    const reply = data.choices?.[0]?.message?.content;
    if (!reply) return c.json({ error: "ai_empty" }, 502);
    return c.json({ reply });
  });

  return app;
}
