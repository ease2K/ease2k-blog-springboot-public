import { BookOpen, ChevronRight } from "lucide-react";
import type { EntrySummary } from "@/lib/content";
import type { ReadingData } from "@/lib/reading";

export function RecentReading({ entries, records, lang, open, available }: { entries: EntrySummary[]; records: ReadingData; lang: "zh" | "en"; open: (path: string) => void; available: boolean }) {
    const last = entries.filter(entry => records[entry.id] && !records[entry.id].completed).sort((a, b) => records[b.id].visitedAt - records[a.id].visitedAt)[0];
    if (!last) return null;
    const href = "/entry/" + last.id + "?resume=1", percent = Math.round((records[last.id].progress || 0) * 100);
    return <section className="reading-progress reading-progress-compact">
        <div className="reading-progress-heading"><BookOpen size={18} /><div><h2>{lang === "zh" ? "继续上次阅读" : "Pick up where you left off"}</h2><p>{lang === "en" && last.enTitle ? last.enTitle : last.title} · {percent}%</p></div></div>
        <progress value={percent} max={100} aria-label={lang === "zh" ? "文章阅读进度" : "Article reading progress"} />
        <a className="primary-button" href={href} onClick={event => { if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return; event.preventDefault(); open(href); }}>{lang === "zh" ? "继续阅读" : "Resume"}<ChevronRight size={15} /></a>
        {!available && <small>{lang === "zh" ? "浏览器无法保存，当前进度仅在本页会话中保留。" : "Storage is unavailable. Progress lasts for this page session."}</small>}
    </section>;
}
