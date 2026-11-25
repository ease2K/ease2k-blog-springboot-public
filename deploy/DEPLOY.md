# PostgreSQL 版使用说明

## 启动与站主密码

将压缩包解到一个新目录。在解包目录执行 `./start-local.sh`，浏览器打开 [博客首页](http://127.0.0.1:8089/) 或 [站主工作台](http://127.0.0.1:8089/_owner)。本机需要 JDK 27、PostgreSQL 服务端工具及 OpenSSL。启动脚本在包自己的 `data/` 下创建数据库和连接密码，并仅监听 `127.0.0.1:55432`。工作台的密码凭据及图片存放在 `editor/`。

首次配置站主密码时，在交互式终端执行 `./start-local.sh setup-owner`，输入至少 12 个字符的密码。已有站主凭据时，再执行该命令会更换密码并使旧会话失效。Ctrl+C 停止前台网站，`./start-local.sh stop-db` 停止此目录的数据库。

`./start-local.sh db` 打开数据库终端。一般命令可以通过 `./start-local.sh db 命令 参数` 在本地数据库环境中运行。已安装的 PostgreSQL 也可以通过 `BLOG_DATABASE_URL`、`BLOG_DATABASE_USERNAME` 和 `BLOG_DATABASE_PASSWORD` 接入，应用账号需要在专用数据库的默认 schema 中建表。对外部实例执行迁移和备份时另设对应的 libpq 变量 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`，通过 `PGPASSFILE` 提供密码。

## 导入旧 SQLite 网站数据

使用旧站停止写入后的数据目录或完整备份。源目录需包含 `content.sqlite` 和 `owner.json`，上传图片放在 `media/`。如果已有测试站在用默认数据库，可以使用另一端口和另一目录。下面的例子从空库开始，导入后才启动应用。

```bash
export BLOG_LOCAL_DATA_DIRECTORY="$PWD/data/imported"
export BLOG_LOCAL_DATABASE_PORT=55433
export BLOG_EDITOR_DIRECTORY="$PWD/data/imported-editor"

./start-local.sh db python3 deploy/copy-editor.py /path/to/sqlite-editor "$BLOG_EDITOR_DIRECTORY"
./start-local.sh
```

脚本通过 SQLite 快照读取已提交数据，把在线文章、草稿、修订记录、删除标记、专栏和图片索引导入 PostgreSQL，并复制站主凭据与图片。使用原密码重新登录。若旧站有独立的基础 `articles.json`，启动时将 `BLOG_CONTENT_DIRECTORY` 指向对应内容目录。在线记录仍按同 ID 覆盖基础文章。

## 备份与恢复

默认工作台目录是 `editor/`。使用与数据库服务器同版本或更新的 PostgreSQL 客户端：

```bash
./start-local.sh db python3 deploy/backup-editor.py create "$PWD/editor" "$PWD/backups"
python3 deploy/backup-editor.py verify /path/to/snapshot
```

备份内含 `content.pgdump`、凭据、图片和 SHA-256 清单，默认保留最近 14 份。备份省略旧会话和登录尝试数据。自定义基础文章和网站启动配置需要另外保存。

恢复时使用新的数据库实例和站主目录。数据库中应无应用表。如 `55433` 已被占用，改用其他空闲端口。

```bash
export BLOG_LOCAL_DATA_DIRECTORY="$PWD/data/restored"
export BLOG_LOCAL_DATABASE_PORT=55433
export BLOG_EDITOR_DIRECTORY="$PWD/data/restored-editor"

./start-local.sh db python3 deploy/backup-editor.py restore /path/to/snapshot "$BLOG_EDITOR_DIRECTORY"
./start-local.sh
```

## Linux 配置示例

`deploy/` 内的 systemd 和 Nginx 文件可用于搭建单独的 Linux 实例。应用模板使用 `/opt/ease2k-blog-springboot-postgresql/current` 作为解包目录，服务账户为 `easeblog-postgres`，配置文件为 `/etc/ease2k-blog-springboot-postgresql.env`。根据 `blog.env.example` 配置 PostgreSQL、站点来源与私有编辑目录。启用工作台时，在配置文件与 `nginx-owner-proxy.conf.example` 中设置同一个新代理密钥。备份单位使用同一环境文件和独立的 `/var/backups/ease2k-blog-postgresql` 目录。
