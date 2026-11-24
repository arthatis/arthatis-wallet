import { describe, expect, it } from "vitest";
import { amountText, evaluateAmount, formatMinor, groupAmount } from "../src/app/model";

describe("amounts per currency", () => {
  it("formats with the currency's own separators", () => {
    expect(formatMinor(14_125_000, "IDR")).toBe("Rp14.125.000");
    expect(formatMinor(106_079, "USD")).toBe("$1,060.79");
    expect(formatMinor(123_450, "MYR")).toBe("RM1,234.50");
    expect(formatMinor(123_456, "EUR")).toBe("€1.234,56");
    expect(formatMinor(-5, "EUR")).toBe("−€0,05");
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
  });
  it("round-trips a stored amount into an input", () => {
    expect(amountText(123_450, "USD")).toBe("1,234.5");
    expect(amountText(123_400, "EUR")).toBe("1.234");
    expect(amountText(15_000, "IDR")).toBe("15.000");
    expect(amountText(0, "IDR")).toBe("");
  });
});
