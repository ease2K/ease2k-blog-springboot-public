import type { SeriesInfo } from "./series";

export type Entry = {
    searchSnippet?: string;
    searchLanguage?: "en";
    id: string;
    kind: "daily" | "article" | "project";
    title: string;
    enTitle: string;
    summary: string;
    enSummary: string;
    body: string;
    enBody: string;
    date: string;
    tags: string[];
    read: number;
    sample?: boolean;
    hasBody?: boolean;
    hasEnglishBody?: boolean;
    visibility?: "public" | "private";
    section?: "competitions";
    series?: SeriesInfo;
    playground?: { html: string; css: string; js: string };
    featured?: boolean;
    modifiedAt?: string;
    updateNote?: string;
    enUpdateNote?: string;
    environment?: string;
    project?: {
        status: "active" | "complete" | "paused";
        stack: string[];
        problem: string;
        enProblem?: string;
        outcomes: string[];
        enOutcomes?: string[];
        screenshot?: string;
        screenshotAlt?: string;
        sourceUrl?: string;
        demoUrl?: string;
    };
};

export const profiles = {
    codeforces: "https://codeforces.com/",
    atcoder: "https://atcoder.jp/",
    github: "https://github.com/ease2K",
};

export const portals = {
    spring: "https://spring.io/guides",
};

export const authoredProblems = [
    {
        id: "request-tracing", title: "跟踪一条 HTTP 请求", enTitle: "Trace an HTTP request",
        summary: "在文章详情接口设置断点，观察各层如何处理文章 ID。", enSummary: "Set a breakpoint at the article endpoint and follow the ID through each layer.",
        href: "/api/entries/spring-web-request", topic: "Java / HTTP", tutorialId: "spring-web-request",
    },
];

export type Award = {
    id: string;
    event: string;
    award: string;
    date: string;
    team?: string;
    description: string;
    certificate?: string;
    pinned?: boolean;
    enEvent?: string;
    enAward?: string;
    enDescription?: string;
};
export const awards: Award[] = [];
export const orderedAwards = [...awards].sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned) || b.date.localeCompare(a.date));

export const technologyTopics = ["Java", "MySQL", "HTML", "CSS", "JavaScript"] as const;
export const sections = ["daily", "articles", "projects", "algorithms", "java", "mysql", "html", "css", "javascript"] as const;
export type Section = typeof sections[number];
export function inSection(entry: Entry, section: string) {
    if (section === "daily") return entry.kind === "daily";
    if (section === "projects") return entry.kind === "project";
    if (section === "articles") return true;
    if (section === "competitions") return entry.section === "competitions";
    if (section === "algorithms") return entry.tags.includes("算法");
    if (technologyTopics.some(topic => topic.toLowerCase() === section)) return entry.tags.some(value => value.trim().toLowerCase() === section || (section === "javascript" && value.trim().toLowerCase() === "js"));
    if (section === "cpp") return entry.tags.includes("C++");
    return false;
}
