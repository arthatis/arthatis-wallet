import { describe, expect, it } from "vitest";
import { laterRecord } from "../src/lww.ts";

const row = (updatedAt: number, deletedAt: number | null = null) => ({
  entity: "transaction",
  id: "t1",
  updatedAt,
  deletedAt,
});

describe("laterRecord", () => {
  it("stores the first copy", () => {
    expect(laterRecord(undefined, row(10))).toMatchObject({ updatedAt: 10 });
  });
  it("keeps the later timestamp, including a delete", () => {
    expect(laterRecord(row(10), row(12, 12))).toMatchObject({ deletedAt: 12 });
    expect(laterRecord(row(12, 12), row(10))).toBe("stored");
  });
  it("keeps the stored copy when timestamps match", () => {
    expect(laterRecord(row(10), row(10))).toBe("stored");
  });
});
