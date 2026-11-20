type Localized = Record<"zh" | "en", string>;
type Release = { date: string; title: Localized; changes: Localized[] };

export { changelogPath, changelogCopy } from "./changelog-meta";

export const releases: Release[] = [
    {
        date: "2026-11-20",
        title: { zh: "Spring Boot 公开学习版", en: "Spring Boot learning edition" },
        changes: [
            { zh: "示例覆盖文章查询、Markdown 渲染、草稿和发布。", en: "Examples cover article queries, Markdown rendering, drafts, and publishing." },
        ],
    },
];
