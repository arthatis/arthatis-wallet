import { describe, expect, it } from "vitest";
import { amountText, compactOf, formatCompact, evaluateAmount, formatMinor, groupAmount } from "../src/app/model";

describe("amounts per currency", () => {
  it("formats with the currency's own separators", () => {
    expect(formatMinor(141_250_000_000, "IDR")).toBe("Rp14.125.000");
    expect(formatMinor(12_345_000, "IDR")).toBe("Rp1.234,5");
    expect(formatMinor(1, "IDR")).toBe("Rp0,0001");
    expect(formatMinor(10_607_900, "USD")).toBe("$1,060.79");
    expect(formatMinor(10_607_925, "USD")).toBe("$1,060.7925");
    expect(formatMinor(12_345_000, "MYR")).toBe("RM1,234.50");
    expect(formatMinor(12_345_600, "EUR")).toBe("€1.234,56");
    expect(formatMinor(-500, "EUR")).toBe("−€0,05");
  });
  it("groups while typing", () => {
    expect(groupAmount("1234567", "IDR")).toBe("1.234.567");
    expect(groupAmount("1234567.5", "USD")).toBe("1,234,567.5");
    expect(groupAmount("1234567,5", "EUR")).toBe("1.234.567,5");
  });
  it("reads what it displays", () => {
    expect(evaluateAmount("1.234.567", "IDR")).toBe(1_234_567);
    expect(evaluateAmount("1,234.56", "USD")).toBe(1234.56);
    expect(evaluateAmount("1.234,56", "EUR")).toBe(1234.56);
    expect(evaluateAmount("12k+5k", "IDR")).toBe(17000);
    expect(evaluateAmount("1.234,5", "IDR")).toBe(1234.5);
  });
  it("round-trips a stored amount into an input", () => {
    expect(amountText(12_345_000, "USD")).toBe("1,234.5");
    expect(amountText(12_340_000, "EUR")).toBe("1.234");
    expect(amountText(150_000_000, "IDR")).toBe("15.000");
    expect(amountText(12_345_000, "IDR")).toBe("1.234,5");
    expect(amountText(0, "IDR")).toBe("");
  });
});

describe("compact amounts", () => {
  it("shortens like the phone", () => {
    expect(formatCompact(41_500_000, "IDR")).toBe("Rp4,1k");
    expect(formatCompact(67_500_000_000, "USD")).toBe("$6.7m");
    expect(compactOf("Rp14.125.000")).toBe("Rp14,1m");
    expect(compactOf("+S$2,500.00")).toBe("+S$2.5k");
    expect(compactOf("12 transaksi")).toBeNull();
  });
});
