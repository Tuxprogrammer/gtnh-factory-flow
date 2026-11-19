import { execFileSync } from "node:child_process";
import type { NextConfig } from "next";

// The shared view tokens are generated, not committed: make sure they exist for every way of starting next.
execFileSync(process.execPath, ["tools/tokens/generate.mjs"], { cwd: process.cwd(), stdio: "inherit" });

const datasetBackendUrl = process.env.GTNH_DATASET_BACKEND_URL?.replace(/\/+$/, "");

const nextConfig: NextConfig = {
  outputFileTracingRoot: process.cwd(),
  turbopack: {
    root: process.cwd(),
  },
  async headers() {
    return [
      {
        source: "/datasets/gtnh/:version/textures/icons/:path*",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=31536000, immutable",
          },
        ],
      },
      {
        // Rendered item and fluid art carries a content hash in the basename
        // (the pipeline renames on any byte change), so these are immutable
        // too - and they are most of what a returning browser re-downloads.
        source: "/datasets/gtnh/:version/textures/rendered/:path*",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=31536000, immutable",
          },
        ],
      },
      {
        // The compiled core's files carry a hash of their content in the name (tools/copy-core.mjs), so one never changes
        // under its name: a returning visitor downloads none of them. manifest.json is the one that names the current set.
        source: "/core/:dir(wasm-gc|js)/:file",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=31536000, immutable",
          },
        ],
      },
      {
        source: "/core/manifest.json",
        headers: [
          {
            key: "Cache-Control",
            value: "no-cache",
          },
        ],
      },
    ];
  },
  async rewrites() {
    const appRewrites = [
      {
        source: "/umami",
        destination: "http://127.0.0.1:8582/umami",
      },
      {
        source: "/umami/:path*",
        destination: "http://127.0.0.1:8582/umami/:path*",
      },
      {
        source: "/_umami/:path*",
        destination: "http://127.0.0.1:8582/umami/:path*",
      },
    ];

    if (!datasetBackendUrl) {
      return appRewrites;
    }

    return {
      beforeFiles: [
        {
          source: "/api/datasets/:path*",
          destination: `${datasetBackendUrl}/api/datasets/:path*`,
        },
        {
          source: "/datasets/gtnh/:path*",
          destination: `${datasetBackendUrl}/datasets/gtnh/:path*`,
        },
      ],
      afterFiles: appRewrites,
    };
  },
};

export default nextConfig;
