/**
 * The shape language of `conformance/wire/shapes.json` (described in its `$language` entry), checked in Node.
 * `jvm/mod/src/test/java/com/gtnhplanner/cloud/wire/WireShape.java` is the same checker for the game's side; the two
 * agree on every shape of the file because both run the same examples (wire-shape.test.ts, WireShapeTest).
 *
 * A mismatch is reported as a list of "path: what was wrong" lines, so a failing exchange says where.
 */

export type Shapes = Record<string, unknown>;

const PRIMITIVES = new Set(["string", "number", "integer", "boolean", "null", "object", "array", "any"]);

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function primitive(name: string, value: unknown): boolean {
  switch (name) {
    case "string":
      return typeof value === "string";
    case "number":
      return typeof value === "number" && Number.isFinite(value);
    case "integer":
      return typeof value === "number" && Number.isInteger(value);
    case "boolean":
      return typeof value === "boolean";
    case "null":
      return value === null;
    case "object":
      return isObject(value);
    case "array":
      return Array.isArray(value);
    case "any":
      return true;
    default:
      throw new Error(`unknown primitive ${name}`);
  }
}

/** The problems with `value` against `shape` (empty: it matches). Shape names resolve in `shapes`. */
export function shapeProblems(shapes: Shapes, shape: unknown, value: unknown, at = "$"): string[] {
  if (typeof shape === "string") {
    const options = shape.split("|").map((s) => s.trim());
    const tried: string[][] = [];
    for (const option of options) {
      let problems: string[];
      if (option.startsWith("$")) {
        const named = shapes[option.slice(1)];
        if (named === undefined) {
          throw new Error(`unknown shape ${option}`);
        }
        problems = shapeProblems(shapes, named, value, at);
      } else if (PRIMITIVES.has(option)) {
        problems = primitive(option, value) ? [] : [`${at}: expected ${option}, got ${describe(value)}`];
      } else {
        throw new Error(`unknown shape word ${option}`);
      }
      if (problems.length === 0) {
        return [];
      }
      tried.push(problems);
    }
    return options.length === 1 ? tried[0]! : [`${at}: matches none of ${shape} (got ${describe(value)})`];
  }
  if (Array.isArray(shape)) {
    if (shape.length !== 1) {
      throw new Error(`an array shape holds one element shape, got ${shape.length}`);
    }
    if (!Array.isArray(value)) {
      return [`${at}: expected array, got ${describe(value)}`];
    }
    return value.flatMap((item, index) => shapeProblems(shapes, shape[0], item, `${at}[${index}]`));
  }
  if (isObject(shape)) {
    const keys = Object.keys(shape);
    if (keys.length === 1 && keys[0] === "=") {
      return JSON.stringify(shape["="]) === JSON.stringify(value)
        ? []
        : [`${at}: expected ${JSON.stringify(shape["="])}, got ${describe(value)}`];
    }
    if (keys.length === 1 && keys[0] === "in") {
      const allowed = shape.in as unknown[];
      return allowed.some((candidate) => JSON.stringify(candidate) === JSON.stringify(value))
        ? []
        : [`${at}: expected one of ${JSON.stringify(allowed)}, got ${describe(value)}`];
    }
    if (!isObject(value)) {
      return [`${at}: expected object, got ${describe(value)}`];
    }
    const problems: string[] = [];
    for (const key of keys) {
      const optional = key.endsWith("?");
      const name = optional ? key.slice(0, -1) : key;
      if (!(name in value)) {
        if (!optional) {
          problems.push(`${at}.${name}: missing`);
        }
        continue;
      }
      problems.push(...shapeProblems(shapes, shape[key], value[name], `${at}.${name}`));
    }
    return problems;
  }
  throw new Error(`a shape is a string, an array or an object, got ${describe(shape)}`);
}

function describe(value: unknown): string {
  const text = JSON.stringify(value);
  return text === undefined ? "undefined" : text.length > 80 ? `${text.slice(0, 77)}...` : text;
}

/** The value at a dotted path (`design.name`, `plans.0.id`), or undefined. */
export function valueAt(value: unknown, dotted: string): unknown {
  let at: unknown = value;
  for (const part of dotted.split(".")) {
    if (Array.isArray(at)) {
      at = at[Number(part)];
    } else if (isObject(at)) {
      at = at[part];
    } else {
      return undefined;
    }
  }
  return at;
}

/** Problems with `value` not holding each `expected` path/value pair. */
export function expectProblems(value: unknown, expected: Record<string, unknown>): string[] {
  const problems: string[] = [];
  for (const [dotted, want] of Object.entries(expected)) {
    const got = valueAt(value, dotted);
    if (JSON.stringify(got) !== JSON.stringify(want)) {
      problems.push(`${dotted}: expected ${JSON.stringify(want)}, got ${describe(got)}`);
    }
  }
  return problems;
}
