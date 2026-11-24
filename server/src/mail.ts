import nodemailer from "nodemailer";
import type { Mailer } from "./context.ts";

/**
 * Builds the outgoing mail transport from the environment:
 * SMTP only (your own server such as Postfix, or any SMTP relay), otherwise none.
 * Without a transport the server still works; email recovery is simply unavailable.
 */
export function createMailer(env: NodeJS.ProcessEnv): Mailer | null {
  const from = env.MAIL_FROM ?? "Arthatis Wallet <wallet@localhost>";
  if (env.SMTP_HOST) {
    const port = Number(env.SMTP_PORT ?? 587);
    const transport = nodemailer.createTransport({
      host: env.SMTP_HOST,
      port,
      // 465 = implicit TLS; 587/25 upgrade with STARTTLS. SMTP_SECURE overrides.
      secure: env.SMTP_SECURE ? env.SMTP_SECURE === "true" : port === 465,
      auth: env.SMTP_USER ? { user: env.SMTP_USER, pass: env.SMTP_PASS ?? "" } : undefined,
      requireTLS: env.SMTP_REQUIRE_TLS !== "false" && port !== 465 && !!env.SMTP_USER,
    });
    return async ({ to, subject, html }) => { await transport.sendMail({ from, to, subject, html }); };
  }
  return null;
}
