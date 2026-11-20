# 部署与迁移 Spring Boot 博客

Spring Boot 用 JDK 27 提供公开网站、站主工作台和 API。文章、草稿、修订历史、专栏和站主图片沿用 Node.js 版的 SQLite 表和数据目录格式。服务器运行 JAR 时只需 JDK。

## 1. 构建并解包

在项目目录运行 `./build-release.sh`。将 `target/release/` 下的部署包和 SHA-256 校验文件上传到服务器。

```bash
sha256sum -c ease2k-blog-springboot.tar.gz.sha256
mkdir release-1
tar -xzf ease2k-blog-springboot.tar.gz -C release-1
```

## 2. 安装独立服务

以下命令在 Linux 服务器上用 root 执行。可以先选择独立的 HTTPS 测试子域名，保留原站入口。

```bash
getent passwd easeblog-spring >/dev/null || useradd --system --shell /usr/sbin/nologin easeblog-spring
install -d -m 755 /opt/ease2k-blog-springboot/releases/release-1
install -m 644 release-1/blog.jar /opt/ease2k-blog-springboot/releases/release-1/blog.jar
ln -s /opt/ease2k-blog-springboot/releases/release-1 /opt/ease2k-blog-springboot/current

install -d -m 755 /var/lib/ease2k-blog-springboot/releases/content-1
install -m 644 release-1/content.example/articles.json /var/lib/ease2k-blog-springboot/releases/content-1/articles.json
ln -s /var/lib/ease2k-blog-springboot/releases/content-1 /var/lib/ease2k-blog-springboot/content

install -m 600 release-1/blog.env.example /etc/ease2k-blog-springboot.env
install -m 644 release-1/ease2k-blog-springboot.service /etc/systemd/system/
install -d -m 755 /etc/nginx/snippets
install -m 644 release-1/nginx-owner-proxy.conf.example /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
```

将 `BLOG_PUBLIC_ORIGIN` 设置为测试域名或站点正式 HTTPS 来源。确认 `ExecStart` 指向服务器的 JDK 27。端口有冲突时，同时调整环境文件和 Nginx 上游端口。

## 3. 准备站主数据

已有 Node.js 站时，优先从其经过验证的完整备份目录复制。目录需要包含 `content.sqlite`、`owner.json` 和原有 `media/`。用 **Python 3** 执行：

```bash
editor=/var/lib/ease2k-blog-springboot/editor
python3 release-1/copy-editor.py /path/to/node-editor-backup "$editor"
chown -R easeblog-spring:easeblog-spring "$editor"
```

复制使用 SQLite 备份接口，把 WAL 中已提交的事务一并复制到新数据库。副本保留原密码、文章和草稿历史，并清除旧登录会话。工作台应使用原密码重新登录。随包的 `articles.json` 来自原仓库的公开基础文章，SQLite 继续覆盖同 ID 的版本和删除标记。若生产站使用了不同的初始文章集，先把相同的基础内容放入 `BLOG_CONTENT_DIRECTORY`。

新建工作台时，先建私有目录，再交互式设置密码：

```bash
install -d -m 700 -o easeblog-spring -g easeblog-spring "$editor"
sudo -u easeblog-spring /opt/jdk-27/bin/java -jar /opt/ease2k-blog-springboot/current/blog.jar setup-owner "$editor"
```

在 `/etc/ease2k-blog-springboot.env` 中启用 `BLOG_EDITOR_DIRECTORY=/var/lib/ease2k-blog-springboot/editor`。首次配置 HTTPS 代理时，为 Spring Boot 和 Nginx 写入同一份新密钥：

```bash
umask 077
owner_proxy=$(openssl rand -hex 32)
printf '\nBLOG_OWNER_PROXY_SECRET=%s\n' "$owner_proxy" >> /etc/ease2k-blog-springboot.env
printf 'proxy_set_header X-Ease2k-Owner-Proxy "%s";\n' "$owner_proxy" > /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
chmod 600 /etc/nginx/snippets/ease2k-blog-springboot-owner.conf
unset owner_proxy
```

密钥在环境文件中保留一条有效配置即可。若只需要只读站点，可保留环境文件中的工作台配置为注释，Nginx 使用随包的空头部覆盖配置。

## 4. 启动与 HTTPS 验收

```bash
systemctl daemon-reload
systemctl enable --now ease2k-blog-springboot
curl --fail http://127.0.0.1:8088/healthz
journalctl -u ease2k-blog-springboot -n 50 --no-pager
```

将 `nginx.conf.example` 适配到测试域名、有效证书路径和对应上游，再执行 `nginx -t` 并重新加载 Nginx。工作台的生产入口为 `https://你的域名/_owner`。先检查直接打开文章、规范地址和 RSS，再使用站主账号确认历史文章、编辑草稿和图片能读取。测试发布应使用测试站数据副本。

## 5. 正式切换与回退

- 切换前保存原 Nginx 配置和完整站主数据备份，在短暂停写窗口内停止旧站写入。用最新数据重新生成一份私有副本，将 Spring Boot 指向该副本并重启。两个进程分别使用自己的数据目录。
- 核对原密码登录、草稿恢复和最近发布文章。检查通过后，才把现有正式域名的 Nginx 上游切换到 Spring Boot，并把 `BLOG_PUBLIC_ORIGIN` 改为该正式域名。保留原 TLS 配置。
- 如果新站还没有写入新内容，可把 Nginx 切回原上游。若已经有新发布或草稿，先停止 Java 站写入，备份两个目录，再用同格式的新副本恢复到旧站数据目录后切回。切回后需重新登录。
- 完整恢复需要 SQLite、图片和站主配置。站主界面导出的 JSON 适合核对内容，完整恢复应使用数据目录备份。

## 保留原站的定时备份和 Mac 拉取

如果服务器已运行原站的 `ease2k-blog-backup.timer`，可在正式切换后添加随包的 systemd 覆盖配置。它保留原来的备份路径、校验、保留策略和只读拉取通道，只把输入切换到 Spring Boot 的编辑目录。保留下来的 Node.js 程序在这里负责备份。

```bash
install -d -m 755 /etc/systemd/system/ease2k-blog-backup.service.d
install -m 644 release-1/legacy-backup.override.conf.example /etc/systemd/system/ease2k-blog-backup.service.d/springboot.conf
systemctl daemon-reload
systemctl start ease2k-blog-backup.service
journalctl -u ease2k-blog-backup.service -n 20 --no-pager
systemctl list-timers ease2k-blog-backup.timer
```

回退到 Node.js 站时一并恢复备份服务的输入目录。没有原备份服务的服务器也可以用 `copy-editor.py` 手动生成独立副本。

## 仅导入公开内容

`scripts/import-public.mjs` 可以从原站公开 `/api/blog` 的 JSON 生成独立公开内容目录。这条流程适用于只读预览。要迁移草稿、历史和站主账号，使用上面的 SQLite 数据目录流程。
