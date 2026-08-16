import type { NextConfig } from "next";

const isDev = process.env.NODE_ENV !== "production";

const nextConfig: NextConfig = {
  // Static export is for the jar. Dev uses rewrites to the running service on :8130.
  output: isDev ? undefined : "export",
  images: { unoptimized: true },
  trailingSlash: false,
  async rewrites() {
    if (!isDev) return [];
    return [
      { source: "/api/:path*", destination: "http://127.0.0.1:8130/api/:path*" },
      { source: "/health", destination: "http://127.0.0.1:8130/health" },
    ];
  },
};

export default nextConfig;
