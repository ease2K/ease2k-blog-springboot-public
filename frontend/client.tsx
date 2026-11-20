import { createRoot } from "react-dom/client";
import Blog, { type View } from "./components/blog";
import { bodyFormat, prepareBody } from "./components/article-body";
import type { Entry } from "./lib/content";
import "./styles/globals.css";
import "./styles/corners.css";
import "./styles/reading.css";
import { smoothCorners } from "./lib/corners";

smoothCorners();

let initial: { initialData?: View; initialEntry?: Entry | null; initialLang?: "zh" | "en"; ownerEnabled?: boolean } = {};
const bootstrap = document.getElementById("blog-bootstrap");
try { if (bootstrap?.textContent) initial = JSON.parse(bootstrap.textContent); } catch {}
const initialEntry = initial.initialEntry || undefined;
const initialLang = initial.initialLang || (/^zh(?:-|$)/i.test(navigator.language) ? "zh" : "en");
const app = <Blog initialPath={window.location.pathname}
    initialData={initial.initialData} initialEntry={initialEntry} initialLang={initialLang} ownerEnabled={initial.ownerEnabled} />;
const root = document.getElementById("root")!;

if (initialEntry) {
    const text = initialLang === "en" && initialEntry.enBody || initialEntry.body;
    // 保留服务端正文，等本页需要的排版模块加载完再挂载 React。
    void prepareBody(bodyFormat(text)).catch(() => {}).then(() => createRoot(root).render(app));
} else {
    createRoot(root).render(app);
}
