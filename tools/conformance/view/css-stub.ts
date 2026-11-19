/**
 * Lets a Node run (not vitest, which handles styles itself) import a module whose chain reaches a stylesheet: the
 * generator only runs the website's view code, which never reads a style. Import this first.
 */
const loaders = (require as unknown as { extensions: Record<string, (m: { exports: unknown }) => void> }).extensions;
loaders[".css"] = (module) => {
  module.exports = {};
};
