import { createHash } from "node:crypto";
import { realpathSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";

import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import vueDevTools from "vite-plugin-vue-devtools";

// https://vite.dev/config/
export default defineConfig({
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: { "/api": "http://localhost:8080" },
  },
  plugins: [
    vue(),
    {
      name: "smarttrip-local-health",
      configureServer(server) {
        const projectId = createHash("sha256")
          .update(
            realpathSync(fileURLToPath(new URL(".", import.meta.url)))
              .replaceAll("\\", "/")
              .toLowerCase(),
          )
          .digest("hex");
        server.middlewares.use("/__smarttrip_health", (_req, res) => {
          res.setHeader("Content-Type", "application/json; charset=utf-8");
          res.end(JSON.stringify({ app: "smarttrip", projectId }));
        });
      },
    },
    ...(process.env.SMARTTRIP_DEVTOOLS === "true" ? [vueDevTools()] : []),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
