import { describe, expect, it } from "vitest";
import { Rates, buildTransfer, emptyLedger, flow, rateFromAmounts, toMinor, totalBalance, type Wallet } from "../src/app/model";

const w = (id: string, currency: string, openingMinor = 0, kind: Wallet["kind"] = "biasa"): Wallet =>
  ({ id, name: id, kind, currency, openingMinor, position: 0, updatedAt: 0, deletedAt: null });

describe("round 2", () => {
  it("never counts an opening balance as income", () => {
    const l = { ...emptyLedger(), wallets: [w("cash", "IDR", toMinor(5_000_000, "IDR"))] };
    expect(flow(l, "IDR", new Rates(), 0, Date.now()).income).toBe(0);
    expect(totalBalance(l, "IDR", new Rates())).toBe(toMinor(5_000_000, "IDR"));
  });
  it("derives the rate from both amounts and takes the fee from the recipient", () => {
    const bca = w("bca", "IDR"), wise = w("wise", "USD");
    expect(rateFromAmounts(toMinor(1_645_000, "IDR"), "IDR", toMinor(100, "USD"), "USD")).toBeCloseTo(16450);
    const tx = buildTransfer(bca, wise, toMinor(1_645_000, "IDR"), new Rates(), null, 0, { receivedMinor: toMinor(100, "USD"), feeMinor: toMinor(1, "USD") })!;
    expect(tx.toAmountMinor).toBe(toMinor(99, "USD"));
    expect(tx.feeMinor).toBe(toMinor(1, "USD"));
    const same = buildTransfer(bca, w("ovo", "IDR"), toMinor(100_000, "IDR"), new Rates(), null, 0, { feeMinor: toMinor(2_500, "IDR"), categoryId: "transfer" })!;
    expect(same.toAmountMinor).toBe(toMinor(97_500, "IDR"));
    expect(same.categoryId).toBe("transfer");
    expect(buildTransfer(bca, w("ovo", "IDR"), toMinor(1_000, "IDR"), new Rates(), null, 0, { feeMinor: toMinor(2_000, "IDR") })).toBeNull();
  });
});
