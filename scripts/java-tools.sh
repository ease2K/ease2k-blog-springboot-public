maven="${MAVEN_CMD:-mvn}"
if ! command -v "$maven" >/dev/null 2>&1; then
    idea_maven="/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn"
    if [[ -x "$idea_maven" ]]; then
        maven="$idea_maven"
    else
        echo "请安装 Maven，或设置 MAVEN_CMD 指向 Maven 的可执行文件。" >&2
        exit 1
    fi
fi

if [[ "$(uname -s)" == Darwin ]] && jdk27="$(/usr/libexec/java_home -F -v 27 2>/dev/null)"; then
    export JAVA_HOME="$jdk27"
fi

run_maven() {
    if [[ "${BLOG_OFFLINE:-0}" == 1 ]]; then
        "$maven" -o "$@"
    else
        "$maven" "$@"
    fi
}
