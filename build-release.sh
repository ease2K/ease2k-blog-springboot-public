#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source scripts/java-tools.sh

if [[ "${BLOG_OFFLINE:-0}" == 1 ]]; then
    npm --prefix frontend ci --offline
else
    npm --prefix frontend ci --prefer-offline
fi
npm --prefix frontend run check
npm --prefix frontend run build

# 在临时目录构建，避免清理正在由 IDEA 使用的 target 目录。
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT
cp -R pom.xml src "$build_dir/"
mkdir -p "$build_dir/frontend" "$build_dir/release/content.example" target/release
cp -R frontend/dist "$build_dir/frontend/"
run_maven -B -ntp -f "$build_dir/pom.xml" package
cp "$build_dir"/target/*.jar "$build_dir/release/blog.jar"
cp deploy/*.example deploy/*.service deploy/DEPLOY.md deploy/copy-editor.py "$build_dir/release/"
cp src/main/resources/data/articles.json "$build_dir/release/content.example/"

archive=ease2k-blog-springboot.tar.gz
COPYFILE_DISABLE=1 tar -czf "$build_dir/$archive" -C "$build_dir/release" .
mv "$build_dir/$archive" "target/release/$archive"
(
    cd target/release
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$archive" > "$archive.sha256"
    else
        shasum -a 256 "$archive" > "$archive.sha256"
    fi
)
printf '部署包已生成 %s/target/release/%s\n' "$PWD" "$archive"
