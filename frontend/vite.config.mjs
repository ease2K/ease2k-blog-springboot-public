import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

export default defineConfig({
    plugins: [react()],
    resolve: { alias: { "@": fileURLToPath(new URL(".", import.meta.url)) } },
    build: {
        assetsInlineLimit: file => /\.woff2?$/.test(file) ? false : undefined,
        rolldownOptions: {
            input: {
                blog: fileURLToPath(new URL("./index.html", import.meta.url)),
                owner: fileURLToPath(new URL("./owner.html", import.meta.url)),
            },
        },
    },
});
