type Localized = Record<"zh" | "en", string>;
type Release = { date: string; title: Localized; changes: Localized[] };

export { changelogPath, changelogCopy } from "./changelog-meta";

export const releases: Release[] = [
    {
        date: "2026-11-24",
        title: { zh: "博客后端接入 MyBatis", en: "The blog backend now uses MyBatis" },
        changes: [
            { zh: "文章、草稿、历史版本、专栏、图片和登录会话的数据库读写改用 MyBatis。", en: "MyBatis now handles database reads and writes for articles, drafts, revision history, series, images, and sign-in sessions." },
            { zh: "沿用原有 SQLite 数据库，保留文章、草稿、历史版本和图片。", en: "The existing SQLite database continues to store articles, drafts, revision history, and image records." },
        ],
    },
    {
        date: "2026-11-20",
        title: { zh: "Spring Boot 版", en: "Spring Boot edition" },
        changes: [
            { zh: "示例覆盖文章查询、Markdown 渲染、草稿和发布。", en: "Examples cover article queries, Markdown rendering, drafts, and publishing." },
        ],
    },
];
