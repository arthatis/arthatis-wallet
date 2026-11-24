import { Hono } from "hono";
import { cors } from "hono/cors";
import { zValidator } from "@hono/zod-validator";
import type { AppDeps } from "./context.ts";
import { contactSchema } from "./schemas.ts";
import { adminRoutes } from "./routes/admin.ts";
import { aiRoutes } from "./routes/ai.ts";
import { groupRoutes, inviteRoutes } from "./routes/groups.ts";
import { identityRoutes } from "./routes/identity.ts";

const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`);

export function createApp(deps: AppDeps) {
  const app = new Hono();
  app.use("*", cors({ origin: deps.corsOrigin, allowHeaders: ["authorization", "content-type", "x-link-secret"] }));

  app.get("/health", (c) => c.json({ ok: true }));

  const v1 = new Hono();
  v1.route("/", identityRoutes(deps));
  v1.route("/groups", groupRoutes(deps));
  v1.route("/invites", inviteRoutes(deps));
  v1.route("/ai", aiRoutes(deps));
  v1.route("/admin", adminRoutes(deps));

  v1.post("/contact", zValidator("json", contactSchema), async (c) => {
    if (!deps.contactTo || !deps.mailer) return c.json({ error: "contact_not_configured" }, 503);
    const body = c.req.valid("json");
    try {
      await deps.mailer({
        to: deps.contactTo,
        subject: `Kontak Arthatis dari ${body.name}`,
        html: `<p><b>${esc(body.name)}</b> (${esc(body.email)})</p><p>${esc(body.message).replace(/\n/g, "<br>")}</p>`,
      });
    } catch {
      return c.json({ error: "mail_failed" }, 502);
    }
    return c.json({ ok: true });
  });

  app.route("/v1", v1);
  return app;
}
