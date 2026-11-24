import type { EntrySummary } from "./content";
export type SeriesInfo = { id: string; title: string; enTitle?: string; description?: string; enDescription?: string; order: number; cover?: string };
export function seriesCatalog(entries: EntrySummary[]) {
    const groups = new Map<string, EntrySummary[]>();
    for (const entry of entries) {
        if (!entry.series) continue;
        const group = groups.get(entry.series.id) || [];
        group.push(entry); groups.set(entry.series.id, group);
    }
    return [...groups].map(([, chapters]) => {
        chapters.sort((a, b) => a.series!.order - b.series!.order || a.id.localeCompare(b.id));
        return { ...chapters[0].series!, chapters };
    }).sort((a, b) => a.title.localeCompare(b.title));
}
