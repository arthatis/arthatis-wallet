import { describe, expect, it } from "vitest";
import * as C from "../src/app/crypto";

describe("crypto", () => {
  it("seals with aad binding", () => {
    const key = C.randomBytes(32);
    const blob = C.sealText(key, "halo", "transaction|t1");
    expect(C.openText(key, blob, "transaction|t1")).toBe("halo");
    expect(() => C.openText(key, blob, "transaction|t2")).toThrow();
  });

  it("hybrid envelope opens only for the recipient", () => {
    const a = C.newWebKeys(), b = C.newWebKeys();
    const vk = C.randomBytes(32);
    const env = C.sealTo(a.boxPub, a.kemPub, vk);
    expect(C.openFrom(a, env)).toEqual(vk);
    expect(() => C.openFrom(b, env)).toThrow();
    expect(C.webPub(a).length).toBe(32 + 1184);
  });

  it("recovery key wraps data", () => {
    const rk = C.randomBytes(20);
    const blob = C.wrapWithRecoveryKey(rk, C.randomBytes(8), 1024);
    expect(C.unwrapWithRecoveryKey(rk, blob, 1024).length).toBe(8);
    expect(() => C.unwrapWithRecoveryKey(C.randomBytes(20), blob, 1024)).toThrow();
  });

  it("parses recovery keys written in groups", () => {
    expect(C.parseRecoveryKey("AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA")?.length).toBe(20);
    expect(C.parseRecoveryKey("not a key")).toBeNull();
  });
});
