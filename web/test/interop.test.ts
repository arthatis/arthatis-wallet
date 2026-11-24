import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as C from "../src/app/crypto";
import * as G from "../src/app/groupcrypto";

// Cross-implementation vectors shared with the Kotlin `InteropTest` (docs/protocol.md).
const FILE = new URL("../../../docs/test-vectors/interop.json", import.meta.url);

describe("Kotlin ↔ web interop", () => {
  it.runIf(process.env.WRITE_VECTORS === "web")("writes web-side vectors", () => {
    const keys = C.newWebKeys();
    const key = C.randomBytes(32);
    const gk = C.randomBytes(32);
    const groupId = "grp-web"; const epoch = 2;
    writeFileSync(FILE, JSON.stringify({
      web: {
        boxPriv: C.b64(keys.boxPriv), boxPub: C.b64(keys.boxPub), kemPriv: C.b64(keys.kemPriv), kemPub: C.b64(keys.kemPub),
        key: C.b64(key), aad: "transaction|t1", plaintext: "bakso 25k", blob: C.sealText(key, "bakso 25k", "transaction|t1"),
        group: {
          groupId, epoch, recordId: "c-web", envelope: G.wrapKey(gk, epoch, keys.boxPub, keys.kemPub),
          recordJson: '{"amountMinor":125000}', record: G.sealRecord(gk, groupId, epoch, "contribution", "c-web", '{"amountMinor":125000}'),
          profileJson: '{"name":"Randi"}', profile: C.sealText(gk, '{"name":"Randi"}', G.profileAad(groupId)),
        },
        recoveryKey: "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH",
        wrapped: C.b64(C.wrapWithRecoveryKey(C.parseRecoveryKey("AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH")!, new TextEncoder().encode("arsip"), 1024)),
      },
    }, null, 1));
  });

  it.runIf(existsSync(FILE) && !!JSON.parse(readFileSync(FILE, "utf8")).kotlin)("opens what Kotlin sealed", () => {
    const v = JSON.parse(readFileSync(FILE, "utf8"));
    const keys = { boxPriv: C.unb64(v.web.boxPriv), boxPub: C.unb64(v.web.boxPub), kemPriv: C.unb64(v.web.kemPriv), kemPub: C.unb64(v.web.kemPub) };
    expect(C.b64(C.openFrom(keys, C.unb64(v.kotlin.envelope)))).toBe(v.kotlin.secret);
    expect(C.openText(C.unb64(v.kotlin.key), v.kotlin.blob, v.kotlin.aad)).toBe(v.kotlin.plaintext);
    const g = v.kotlin.group;
    const learned = G.unwrapKey(keys, g.envelope)!;
    expect(learned.epoch).toBe(g.epoch);
    expect(G.openRecord(learned.key, g.groupId, learned.epoch, "contribution", g.recordId, g.record)).toBe(g.recordJson);
    expect(C.openText(learned.key, g.profile, G.profileAad(g.groupId))).toBe(g.profileJson);
    // a record cannot be replayed under another epoch or group
    expect(G.openRecord(learned.key, g.groupId, learned.epoch + 1, "contribution", g.recordId, g.record)).toBeNull();
    expect(G.openRecord(learned.key, "other", learned.epoch, "contribution", g.recordId, g.record)).toBeNull();
    const rk = C.parseRecoveryKey(v.web.recoveryKey)!;
    expect(new TextDecoder().decode(C.unwrapWithRecoveryKey(rk, C.unb64(v.kotlin.wrapped), 1024))).toBe("arsip");
  });
});
