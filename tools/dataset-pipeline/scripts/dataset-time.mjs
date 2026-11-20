// The time a dataset carries. SOURCE_DATE_EPOCH (seconds since 1970, the reproducible-builds convention) wins, so two
// exports of one pack can be compared byte for byte; else the time the export itself recorded; else now.
export function datasetTimestamp(exportedAt, env = process.env) {
  const epoch = String(env.SOURCE_DATE_EPOCH ?? "").trim();
  if (/^[0-9]+$/.test(epoch)) {
    return new Date(Number(epoch) * 1000).toISOString();
  }
  if (typeof exportedAt === "string" && !Number.isNaN(Date.parse(exportedAt))) {
    return exportedAt;
  }
  return new Date().toISOString();
}
