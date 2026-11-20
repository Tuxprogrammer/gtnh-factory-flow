import type { MetadataRoute } from "next";
import { siteUrl } from "@/lib/site-url";

export default function robots(): MetadataRoute.Robots {
  const base = siteUrl();
  return {
    rules: [
      {
        userAgent: "*",
        // The card route stays crawlable: Twitterbot honours robots.txt, and
        // blocking it would strip the image off every shared plan's unfurl.
        allow: ["/", "/api/community/plans/*/card"],
        // JSON endpoints and dataset shards have no place in an index.
        disallow: ["/api/", "/datasets/"],
      },
    ],
    // A sitemap address must be absolute: without a configured site there is none to name.
    ...(base ? { sitemap: `${base}/sitemap.xml` } : {}),
  };
}
