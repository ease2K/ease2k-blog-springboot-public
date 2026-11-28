import { ArrowUpRight, Code2 } from "lucide-react";
import type { MouseEvent } from "react";
import type { EntrySummary } from "@/lib/content";

export default function ProjectCard({ entry, lang, open, detail = false }: { entry: EntrySummary; lang: "zh" | "en"; open?: (path: string) => void; detail?: boolean }) {
    const t = (zh: string, en: string) => lang === "zh" ? zh : en;
    const project = entry.project;
    const statuses = { active: t("持续开发", "In development"), complete: t("已完成", "Completed"), paused: t("已暂停", "Paused") };
    const link = "/entry/" + entry.id;
    const title = lang === "en" && entry.enTitle ? entry.enTitle : entry.title;
    const problem = lang === "en" && project?.enProblem ? project.enProblem : project?.problem;
    const description = problem || (lang === "en" && entry.enSummary ? entry.enSummary : entry.summary);
    const outcomes = lang === "en" && project?.enOutcomes?.length ? project.enOutcomes : project?.outcomes;
    const read = (event: MouseEvent<HTMLAnchorElement>) => {
        if (!open || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault(); open(link);
    };
    return <section className={"project-card" + (detail ? " project-card-detail" : "")} data-entry={detail ? undefined : entry.id}>
        <div className="project-card-content">
            {project && <span className="project-status">{statuses[project.status]}</span>}
            <h2>{detail ? t("项目概览", "Project overview") : <a href={link} onClick={read}>{title}</a>}</h2>
            {description && <p>{description}</p>}
            {!!project?.stack.length && <div className="tags">{project.stack.map(value => <span key={value}>{value}</span>)}</div>}
            {!!outcomes?.length && <><h3>{t("实现内容", "What it does")}</h3><ul className="project-outcomes">{outcomes.map(value => <li key={value}>{value}</li>)}</ul></>}
            {(!detail || project?.sourceUrl || project?.demoUrl) && <div className="project-links">
                {!detail && <a href={link} onClick={read}>{t("阅读项目记录", "Read project notes")}<ArrowUpRight size={14} aria-hidden="true" /></a>}
                {project?.sourceUrl && <a href={project.sourceUrl} target="_blank" rel="noopener noreferrer"><Code2 size={14} aria-hidden="true" />{t("项目源码", "Source")}</a>}
                {project?.demoUrl && <a href={project.demoUrl} target="_blank" rel="noopener noreferrer">{t("在线体验", "Open project")}<ArrowUpRight size={14} aria-hidden="true" /></a>}
            </div>}
        </div>
        {project?.screenshot && <a className="project-capture" href={project.screenshot} target="_blank" rel="noopener noreferrer"><img src={project.screenshot} alt={project.screenshotAlt || title} loading="lazy" width="1440" height="1000" /><span>{t("查看实际截图", "View screenshot")}<ArrowUpRight size={13} aria-hidden="true" /></span></a>}
    </section>;
}
