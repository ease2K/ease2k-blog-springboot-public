# 部署与迁移 Spring Boot 博客

生产环境由 Nginx 终止 HTTPS，Spring Boot 在本机回环地址提供公开站点、工作台和 API。下面以 Linux、systemd、`127.0.0.1:8088` 和 `blog.example.com` 为例。构建机需要 [README 中的开发工具](../README.md#本地启动)。服务器运行 JAR 需要 JDK 27，迁移私有数据另外使用 Python 3。

| 路径 | 用途 |
| --- | --- |
| `/opt/ease2k-blog-springboot/releases/` | 按版本保留 JAR 和每日小记脚本 |
| `/opt/ease2k-blog-springboot/current` | systemd 启动的当前应用版本 |
| `/var/lib/ease2k-blog-springboot/content` | 当前基础文章目录，可指向单独的内容版本 |
| `/var/lib/ease2k-blog-springboot/editor` | 私有数据，包含 SQLite、站主凭据和图片 |
| `/etc/ease2k-blog-springboot.env` | systemd 读取的站点配置和代理密钥 |

## 1. 构建并校验部署包

在仓库根目录运行 `./build-release.sh`。把 `target/release/` 中的压缩包和 SHA-256 校验文件传到服务器，在两者所在目录执行：

```bash
sha256sum -c ease2k-blog-springboot.tar.gz.sha256
mkdir release-1
tar -xzf ease2k-blog-springboot.tar.gz -C release-1
```

部署包包含 `blog.jar`、公开基础文章、systemd 服务、Nginx 配置示例、数据复制脚本和每日小记提交脚本。以下命令在解包目录的父目录执行。服务器侧安装命令使用 root。

## 2. 安装程序和基础文章

```bash
getent passwd easeblog-spring >/dev/null || useradd --system --user-group --shell /usr/sbin/nologin easeblog-spring

app=/opt/ease2k-blog-springboot
data=/var/lib/ease2k-blog-springboot

install -d -m 755 "$app/releases/release-1" "$data/releases/content-1"
install -m 644 release-1/blog.jar "$app/releases/release-1/blog.jar"
install -m 755 release-1/daily-note-submit.sh "$app/releases/release-1/daily-note-submit.sh"
install -m 644 release-1/content.example/articles.json "$data/releases/content-1/articles.json"
ln -s "$app/releases/release-1" "$app/current"
ln -s "$data/releases/content-1" "$data/content"

install -m 600 release-1/blog.env.example /etc/ease2k-blog-springboot.env
install -m 644 release-1/ease2k-blog-springboot.service /etc/systemd/system/
install -d -m 755 /etc/nginx/snippets
install -m 644 release-1/nginx-owner-proxy.conf.example /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
```

用文本编辑器修改 `/etc/ease2k-blog-springboot.env`。把 `BLOG_PUBLIC_ORIGIN` 设为实际 HTTPS 来源，例如 `https://blog.example.com`。来源只能包含协议、主机和可选端口。首次迁移可以用独立测试子域名。保留 `SERVER_ADDRESS=127.0.0.1`，若更改 `SERVER_PORT`，同步更改后续检查命令和 Nginx 上游地址。

确认 `ExecStart` 指向服务器的 JDK 27。若使用自定义编辑目录，同时调整环境文件中的 `BLOG_EDITOR_DIRECTORY` 与 systemd 服务文件的 `ReadWritePaths`。只读站可以跳到第 4 步，并保留环境文件中的工作台配置为注释。

## 3. 启用站主工作台

### 从 Node.js 版本迁移

以旧站的完整备份或已停止写入的编辑目录为源。目录应包含 `content.sqlite`、`owner.json` 和原有的 `media/`。传入一个尚未存在的目标目录：

```bash
editor=/var/lib/ease2k-blog-springboot/editor
python3 release-1/copy-editor.py /path/to/node-editor-backup "$editor"
chown -R easeblog-spring:easeblog-spring "$editor"
```

脚本使用 SQLite 的备份接口读取已提交的数据，包括留在 WAL 中的事务。随后执行完整性检查，复制凭据与图片，清除旧会话和登录尝试记录。原密码仍可用于登录。若旧站使用了定制基础文章集，同时复制对应的公开 `articles.json`。SQLite 中的在线记录和删除标记按 ID 覆盖基础文章，基础数据的版本会影响迁移后的公开内容。

### 新建工作台

首次安装且没有旧站数据时，在交互式终端设置密码。密码至少 12 个字符。

```bash
editor=/var/lib/ease2k-blog-springboot/editor
install -d -m 700 -o easeblog-spring -g easeblog-spring "$editor"
runuser -u easeblog-spring -- /opt/jdk-27/bin/java -jar /opt/ease2k-blog-springboot/current/blog.jar setup-owner "$editor"
```

### 配置生产代理

在环境文件中启用 `BLOG_EDITOR_DIRECTORY=/var/lib/ease2k-blog-springboot/editor`。首次设置生产代理时，在两端写入同一份新密钥：

```bash
umask 077
owner_proxy=$(openssl rand -hex 32)
printf '\nBLOG_OWNER_PROXY_SECRET=%s\n' "$owner_proxy" >> /etc/ease2k-blog-springboot.env
printf 'proxy_set_header X-Ease2k-Owner-Proxy "%s";\n' "$owner_proxy" > /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
chmod 600 /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
unset owner_proxy
```

环境文件保留一条有效的 `BLOG_OWNER_PROXY_SECRET` 配置。Nginx 会覆盖同名请求头，并把实际请求主机传给应用。工作台由浏览器通过配置好的 HTTPS 域名打开。站主凭据、代理密钥、SQLite 和私有图片目录应保存在仓库外。

## 4. 启动与验收

```bash
systemctl daemon-reload
systemctl enable --now ease2k-blog-springboot
systemctl status ease2k-blog-springboot --no-pager
curl -fsS http://127.0.0.1:8088/healthz
journalctl -u ease2k-blog-springboot -n 50 --no-pager
```

把 `nginx.conf.example` 适配到实际加载的站点配置，修改 `server_name`、HTTP 重定向目标、有效证书路径和上游端口。已有站点可保留证书及 TLS 配置，按示例设置上游、`Host` 和工作台代理头。执行 `nginx -t && systemctl reload nginx` 后检查：

```bash
origin=https://blog.example.com
curl -fsS "$origin/healthz"
curl -fsS "$origin/api/index"
curl -fsS "$origin/entry/cpp-sort-introduction" | grep -o '<link rel="canonical"[^>]*>'
curl -fsS "$origin/feed.xml"
curl -fsS "$origin/sitemap.xml"
```

- 确认健康检查返回 `{"status":"UP"}`，文章直达地址、RSS 和站点地图使用预期的 HTTPS 域名。若站点采用自定义基础文章，把示例 ID 换成实际存在的公开文章。
- 在浏览器打开 `/_owner`，用站主密码登录并核对旧文章、草稿、历史版本、专栏和图片。用测试副本验证从草稿到发布，再检查公开详情、搜索和 RSS。
- 本机健康检查成功而域名返回 502 时，检查 Nginx 上游地址和端口。工作台返回 404 时，检查编辑目录配置、`Host` 是否与规范来源一致、Nginx 是否覆盖并传递共享密钥。启动失败时检查 JDK 路径、应用日志、基础数据文件及服务账号读写权限。

## 5. 升级和回退

按新版本建一个发布目录，保留当前目录以便回退。先备份编辑目录。基础文章独立管理，升级程序后继续使用 `content` 指向的版本。新的部署包如含有应当同步的基础文章，单独安装到新内容目录并核对同 ID 的在线覆盖记录。

下面以已经解包的 `release-2` 为例：

```bash
app=/opt/ease2k-blog-springboot
readlink "$app/current"

install -d -m 755 "$app/releases/release-2"
install -m 644 release-2/blog.jar "$app/releases/release-2/blog.jar"
install -m 755 release-2/daily-note-submit.sh "$app/releases/release-2/daily-note-submit.sh"
ln -s "$app/releases/release-2" "$app/current.next"
mv -Tf "$app/current.next" "$app/current"
systemctl restart ease2k-blog-springboot
curl -fsS http://127.0.0.1:8088/healthz
```

上线后按第 4 步验收。需要回退应用版本时，按同样方式将 `current` 指回之前确认的发布目录并重启。若升级同时更改了数据结构、内容目录、应用配置或 Nginx，回退前分别核对其兼容性和需要使用的备份。编辑目录保留独立的备份及恢复流程。

### 从旧站切换，以及切回旧站

1. 保存旧站 Nginx 配置并备份完整编辑目录。暂停旧站写入后，用最新数据复制到新的私有目录。两套服务各自使用自己的编辑目录。
2. 用测试域名验证原密码登录、草稿恢复、公开文章和图片。切换正式入口时，将 Spring Boot 的 `BLOG_PUBLIC_ORIGIN` 改为正式域名，并同步调整 Nginx 上游，保留正式域名的 TLS 配置。
3. 若没有在新站写入内容，可恢复旧 Nginx 上游。若新站已有新发布、草稿或图片，先暂停 Java 站写入，备份两边目录，再用新站的完整数据副本恢复旧站目录并校对后切回。恢复副本包含清理过的会话，站主需要重新登录。

## 6. 备份与恢复

完整站主备份包含 SQLite、`owner.json` 和 `media/`。另行保存实际使用的基础文章目录和部署配置。工作台导出的文章 JSON 适合内容核对，完整恢复使用文件备份。

没有原站备份服务时，可以先停止工作台写入，再用 `copy-editor.py` 复制到新的私有目录。恢复时先保存当前目录，把备份复制到新目录并恢复服务账号所有权，再让应用使用该副本。重启后核对草稿、历史、图片和公开文章。

已有原 Node.js 站的 `ease2k-blog-backup.timer` 时，可在正式切换后安装随包提供的 systemd 覆盖文件。它保留原来的备份路径、校验、保留策略和 Mac 只读拉取入口，并将数据输入指向 Spring Boot 编辑目录。此方式继续使用原站的 Node.js 备份程序。

```bash
install -d -m 755 /etc/systemd/system/ease2k-blog-backup.service.d
install -m 644 release-1/legacy-backup.override.conf.example /etc/systemd/system/ease2k-blog-backup.service.d/springboot.conf
systemctl daemon-reload
systemctl start ease2k-blog-backup.service
journalctl -u ease2k-blog-backup.service -n 20 --no-pager
systemctl list-timers ease2k-blog-backup.timer
```

原站路径有定制时先检查覆盖文件中的程序路径、数据目录和备份目标。切回 Node.js 站时一并恢复备份任务的输入目录。

## 7. 定时提交每日小记

提交脚本在 Linux 上需要 `flock`，由站点服务账号运行。Spring Boot 约每 3 秒检查一次编辑目录里的 `daily-note-inbox`。先创建目录并测试应用是否响应：

```bash
editor=/var/lib/ease2k-blog-springboot/editor
submit=/opt/ease2k-blog-springboot/current/daily-note-submit.sh
install -d -m 700 -o easeblog-spring -g easeblog-spring "$editor/daily-note-inbox"
runuser -u easeblog-spring -- env BLOG_EDITOR_DIRECTORY="$editor" bash "$submit" --check
```

检查成功会返回 `{"status":"ready"}`。定时任务可通过 SSH 在服务器调用同一脚本，把 UTF-8 JSON 传入标准输入。以下命令用北京时间当天作为日期，在测试工作台保存一篇私有草稿：

```bash
printf '{"day":"%s","body":"测试工作台的小记内容","draft":true}\n' "$(TZ=Asia/Shanghai date +%F)" |
    runuser -u easeblog-spring -- env BLOG_EDITOR_DIRECTORY="$editor" bash "$submit"
```

`day` 必须是北京时间当天的 `YYYY-MM-DD`，`body` 必填，`title` 可省略。设 `draft` 为 `true` 时存为私有草稿，省略或设为 `false` 时提交发布。响应附带文章 ID，新增时状态为 `draft_saved` 或 `published`。已有同日公开文章、私有记录、编辑草稿或匹配的回收站记录时，保留现有数据并返回所处状态。命令超时后可先检查工作台记录和应用日志，再提交同一日期。

## 8. 仅导入公开内容

只读预览可从旧站导出公开文章，在有 Node.js 的机器上转成独立的内容目录。先将下面的示例域名换成旧站域名：

```bash
mkdir -p data
curl -fsS https://old-blog.example.com/api/blog -o data/public-articles.json
node scripts/import-public.mjs data/public-articles.json ./data/imported /path/to/public-images
BLOG_CONTENT_DIRECTORY="$PWD/data/imported" ./run-local.sh
```

选择尚未存在的导入目录。若文章引用了 `/media/` 图片，最后一个参数须指向已备齐引用文件的公开图片目录。导入脚本生成 `articles.json`，有图片时还会生成 `public-media/`。要迁移草稿、历史和站主账号，使用第 3 步的 SQLite 数据目录流程。
