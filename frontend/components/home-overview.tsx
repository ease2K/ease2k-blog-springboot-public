import { ArrowDown, ArrowRight, ArrowUpRight } from "lucide-react";
import type { ReactNode } from "react";
import BeijingClock from "@/components/beijing-clock";
import type { EntrySummary } from "@/lib/content";

type Lang = "zh" | "en";

export default function HomeOverview({ projects, lang, link, continueReading }: { projects: EntrySummary[]; lang: Lang; link: (href: string, children: ReactNode, className?: string) => ReactNode; continueReading: ReactNode }) {
    const t = (zh: string, en: string) => lang === "zh" ? zh : en;
    const title = (entry: EntrySummary) => lang === "en" && entry.enTitle ? entry.enTitle : entry.title;
    const summary = (entry: EntrySummary) => lang === "en" && entry.enSummary ? entry.enSummary : entry.summary;
    const featured = [...projects].sort((a, b) => Number(!!b.featured) - Number(!!a.featured)).slice(0, 3);
    const stack = [
        { name: t("后端", "Backend"), tools: ["Java", "Spring Boot", "MyBatis"] },
        { name: t("数据库", "Database"), tools: ["PostgreSQL"] },
        { name: t("前端", "Frontend"), tools: ["React", "TypeScript", "Vite"] },
    ];

    return <>
        <section className="journal-cover">
            <div className="journal-landscape" aria-hidden="true" />
            <div className="wrap journal-cover-inner">
                <h1><span className="journal-cover-name">{t("eAs1nGG-_-", "eAs1nGG-_-'s")}</span>{t(" 的个人博客", " personal blog")}</h1>
                <p>{t("这个博客使用 Spring Boot、MyBatis 和 PostgreSQL，前端使用 React 和 TypeScript。示例涵盖 HTTP 请求、草稿编辑和博客项目。", "This blog uses Spring Boot, MyBatis, and PostgreSQL with React and TypeScript. The examples cover HTTP requests, draft editing, and building the blog.")}</p>
                <div className="journal-cover-links">
                    {link("/projects", <>{t("项目记录", "Project notes")}<ArrowRight size={16} aria-hidden="true" /></>, "journal-feature-link")}
                    <a className="journal-stack-link" href="#tech-stack">{t("技术栈", "Tech stack")}<ArrowDown size={15} aria-hidden="true" /></a>
                </div>
                <BeijingClock lang={lang} />
            </div>
        </section>
        <div className="wrap journal-home-body">
            {continueReading}
            <div className="journal-home-columns">
                {!!featured.length && <section className="journal-featured">
                    <div className="journal-section-title"><h2>{t("精选阅读", "Selected reading")}</h2>{link("/projects", <>{t("全部项目", "All projects")}<ArrowUpRight size={14} aria-hidden="true" /></>, "journal-index-link")}</div>
                    {featured.map(entry => <article key={entry.id} className="journal-featured-entry" data-entry={entry.id}>
                        <h3>{link("/entry/" + entry.id, title(entry))}</h3>
                        {summary(entry) && <p>{summary(entry)}</p>}
                        {lang === "en" && entry.hasEnglishBody === false && <small className="journal-entry-language">Full text in Chinese</small>}
                    </article>)}
                </section>}
                <section className="journal-tech-stack" id="tech-stack">
                    <div className="journal-section-title"><h2>{t("技术栈", "Tech stack")}</h2></div>
                    {stack.map(group => <div className="journal-stack-group" key={group.name}>
                        <h3>{group.name}</h3>
                        <ul aria-label={group.name}>{group.tools.map(tool => <li key={tool}>{tool}</li>)}</ul>
                    </div>)}
                </section>
            </div>
        </div>
    </>;
}
