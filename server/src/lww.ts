export type LedgerRow = {
  entity: string;
  id: string;
  updatedAt: number;
  deletedAt: number | null;
};

/** Later `updatedAt` wins. Equal timestamps keep the copy already stored. */
export function laterRecord<T extends LedgerRow>(stored: LedgerRow | undefined, incoming: T): T | "stored" {
  if (!stored) return incoming;
  if (incoming.updatedAt > stored.updatedAt) return incoming;
  return "stored";
}
