import { useEffect, useState } from "react";

const timeZone = "Asia/Shanghai";
const time = new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const dates = {
    zh: new Intl.DateTimeFormat("zh-CN", { timeZone, month: "short", day: "numeric", weekday: "short" }),
    en: new Intl.DateTimeFormat("en-US", { timeZone, month: "short", day: "numeric", weekday: "short" }),
};

export default function BeijingClock({ lang }: { lang: "zh" | "en" }) {
    const [now, setNow] = useState<Date | null>(null);
    const label = lang === "zh" ? "北京时间" : "Beijing time";
    useEffect(() => {
        let timer: ReturnType<typeof setTimeout>;
        function tick() {
            clearTimeout(timer);
            const current = new Date();
            setNow(current);
            if (!document.hidden) timer = setTimeout(tick, 60000 - current.getTime() % 60000);
        }
        tick();
        document.addEventListener("visibilitychange", tick);
        return () => {
            clearTimeout(timer);
            document.removeEventListener("visibilitychange", tick);
        };
    }, []);
    return <time className="journal-clock" dateTime={now?.toISOString()}>{now && <>{dates[lang].format(now)}<span aria-hidden="true"> · </span></>}{label} {now ? time.format(now) : "—:—"}</time>;
}
