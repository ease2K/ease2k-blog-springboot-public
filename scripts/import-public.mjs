import { copyFile, mkdir, mkdtemp, readFile, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";

const [source, target, media] = process.argv.slice(2);
if (!source || !target) {
    console.error("用法: node scripts/import-public.mjs 公开文章.json 新内容目录 [公开图片目录]");
    process.exit(1);
}

const input = JSON.parse(await readFile(source, "utf8"));
const entries = Array.isArray(input) ? input : input.entries;
if (!Array.isArray(entries)) throw new Error("需要文章数组，或包含 entries 数组的 JSON。");

const idPattern = /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/;
const imagePattern = /\/media\/([A-Za-z0-9][A-Za-z0-9_-]{0,127}\.(?:webp|png|jpe?g|avif|gif))(?![\w./-])/gi;
const ids = new Set();
const images = new Set();
const articles = [];
const fields = ["id", "kind", "title", "enTitle", "summary", "enSummary", "body", "enBody",
    "date", "tags", "read", "visibility", "section", "series", "playground", "featured", "modifiedAt",
    "updateNote", "enUpdateNote", "environment", "project"];

for (const entry of entries) {
    if (!entry || entry.visibility === "private" || entry.sample || !entry.body?.trim()) continue;
    if (!idPattern.test(entry.id || "") || ids.has(entry.id) || typeof entry.title !== "string"
        || !entry.title.trim() || !Array.isArray(entry.tags) || !entry.date) {
        throw new Error("公开文章字段不合法或 ID 重复: " + entry.id);
    }
    ids.add(entry.id);
    const article = Object.fromEntries(fields.filter(field => Object.hasOwn(entry, field)).map(field => [field, entry[field]]));
    articles.push(article);
    for (const match of JSON.stringify(article).matchAll(imagePattern)) images.add(match[1]);
}

const targetDirectory = resolve(target);
await stat(targetDirectory).then(() => {
    throw new Error("目标目录已存在，请指定新的内容目录。");
}, error => {
    if (error.code !== "ENOENT") throw error;
});
const stage = await mkdtemp(join(dirname(targetDirectory), ".ease2k-content-"));
try {
    if (images.size) {
        if (!media) throw new Error("文章引用了 /media 图片，请提供这些公开图片所在目录。");
        const mediaRoot = await realpath(media);
        const publicMedia = join(stage, "public-media");
        await mkdir(publicMedia);
        for (const filename of images) {
            const sourceImage = await realpath(join(mediaRoot, filename));
            if (!sourceImage.startsWith(mediaRoot + sep) || !(await stat(sourceImage)).isFile()) {
                throw new Error("图片不在指定公开目录内: " + filename);
            }
            await copyFile(sourceImage, join(publicMedia, filename));
        }
    }
    await writeFile(join(stage, "articles.json"), JSON.stringify(articles, null, 2) + "\n");
    await rename(stage, targetDirectory);
    console.log("导入完成，公开文章 %d 篇，图片 %d 张。", articles.length, images.size);
} catch (error) {
    await rm(stage, { recursive: true, force: true });
    throw error;
}
