import { describe, expect, it } from "vitest";

import { describeDbError } from "./db-error";

describe("describeDbError", () => {
  it("names the code, details, hint and status beside the message", () => {
    expect(
      describeDbError(
        { message: "Could not find the table", code: "PGRST205", details: null, hint: "Reload the schema cache" },
        { status: 404, statusText: "Not Found" },
      ),
    ).toBe("Could not find the table; code PGRST205; Reload the schema cache; HTTP 404 Not Found");
  });

  it("never prints undefined when the answer had no body", () => {
    expect(describeDbError({}, { status: 404 })).toBe("HTTP 404");
    expect(describeDbError({ message: undefined })).toBe("no error text from the database");
  });
});
