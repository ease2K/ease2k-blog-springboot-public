# ease2k 博客 · Spring Boot 版

这是 ease2k 博客的 Spring Boot 公开版，包含公开阅读站点和站主工作台。后端使用 Java 27、Spring Boot 4.1.2 和 Spring MVC，前端沿用原博客的 React、TypeScript 和样式，由 Vite 构建。Spring Boot 在同一个端口提供 HTML、前端资源、JSON API 和 RSS。

仓库自带三篇可直接运行的示例，覆盖 HTTP 请求、编辑草稿和博客项目。公开站点支持文章、专栏、中英文阅读、全文搜索和 RSS。设置站主密码后，可以登录工作台编辑 Markdown、保存草稿、发布、查看历史版本、管理专栏与图片。工作台通过 MyBatis 读写 PostgreSQL，旧 SQLite 数据可以通过脚本导入。

## 本地启动

源码构建需要 JDK 27、Maven、Node.js 22.13.0 或更新版本，以及 npm。本地数据库需要 PostgreSQL 及其命令行工具和 OpenSSL。在 macOS 上，脚本会尝试自动选用已安装的 JDK 27。若 PATH 中没有 Maven，会尝试使用 IntelliJ IDEA 自带的 Maven，也可以通过 `MAVEN_CMD` 指定 Maven 可执行文件。

从源码启动：

```bash
./run-local.sh
```

打开 [本地博客](http://127.0.0.1:8089/) 即可浏览仓库自带的公开文章。脚本将 PostgreSQL 数据放在 `data/postgres/`，数据库仅监听 `127.0.0.1:55432`。用 Ctrl+C 停止网站，用 `./start-local.sh stop-db` 停止该项目的数据库。依赖都已缓存时，可运行 `BLOG_OFFLINE=1 ./run-local.sh`。

使用打好的包时，解压后直接运行 `./start-local.sh`。包内带有构建完成的前端和后端 JAR，运行时需要 JDK 27 和 PostgreSQL 工具。

### 在 IntelliJ IDEA 中调试

1. 打开仓库根目录的 [pom.xml](pom.xml)，把 Project SDK 和 Maven Runner JRE 都设为 JDK 27。
2. 在项目终端运行 `npm --prefix frontend ci` 和 `npm --prefix frontend run build`。前端依赖已安装时，只需重新运行构建命令。
3. 在 IDEA 的 Maven 面板执行 `compile`，将 `frontend/dist/` 复制到 `target/classes/static/`。然后以 Debug 模式运行 [BlogApplication.main()](src/main/java/com/ease2k/blog/BlogApplication.java)。
4. 打开 [HTTP 请求示例](http://127.0.0.1:8089/entry/spring-web-request)，在 `PageController.article()` 打断点观察 HTML 请求。用下文的详情 API 访问同一篇文章，在 `ArticleController.entry()` 打断点观察 JSON 请求。
5. 修改 Java 或基础 JSON 后重新编译并重启。修改前端后重新执行 Vite 构建和 Maven `compile`，再重启应用。

## 目录结构

| 位置 | 职责 |
| --- | --- |
| [src/main/java/com/ease2k/blog/controller/](src/main/java/com/ease2k/blog/controller/) | 公开页面、文章 API、RSS、站点地图和公开图片 |
| [src/main/java/com/ease2k/blog/service/](src/main/java/com/ease2k/blog/service/) | 文章查询与搜索、页面渲染、竞赛 Rating |
| [src/main/java/com/ease2k/blog/repository/](src/main/java/com/ease2k/blog/repository/) | 读入基础文章，根据站主数据生成公开快照 |
| [src/main/java/com/ease2k/blog/owner/](src/main/java/com/ease2k/blog/owner/) | 工作台路由、登录、草稿与发布、专栏和图片 |
| [src/main/java/com/ease2k/blog/config/](src/main/java/com/ease2k/blog/config/) | 站点来源、静态资源缓存和响应头 |
| [src/main/resources/](src/main/resources/) | Spring Boot 配置、基础文章、竞赛获奖 ID 和 Rating 快照 |
| [frontend/](frontend/) | 公开站点与工作台的 React 入口、组件、样式和静态资源 |
| [deploy/](deploy/) 与 [scripts/](scripts/) | PostgreSQL 迁移及备份、Linux 服务配置和构建工具 |

## 从一次请求读代码

访问 `GET /api/entries/spring-web-request` 时，调用关系如下：

```text
ArticleController.entry()
  → ArticleService.findById()
    → ArticleRepository.findById()
      → 从当前公开快照按 ID 查找文章
```

1. [ArticleController](src/main/java/com/ease2k/blog/controller/ArticleController.java) 用 `@GetMapping` 匹配 HTTP 路径，`@PathVariable` 取出 ID。找到时返回包含 `entry` 的 JSON，ID 不存在或文章未公开时返回 404。
2. Spring 通过构造器把 [ArticleServiceImpl](src/main/java/com/ease2k/blog/service/ArticleServiceImpl.java) 作为 `ArticleService` 注入 Controller。它把详情查询委托给 `ArticleRepository`，并负责列表摘要和全文搜索。
3. [SnapshotArticleRepository](src/main/java/com/ease2k/blog/repository/SnapshotArticleRepository.java) 维护不可变的公开文章列表、ID 索引和图片引用集合。[SeedArticles](src/main/java/com/ease2k/blog/repository/SeedArticles.java) 启动时加载基础文章。工作台启用后，仓库从 `OwnerEntries.published()` 获取合并后的公开数据。涉及公开内容的事务提交后会更新版本号，下一次读取按新版本重建快照。
4. 列表和搜索共用按快照版本生成的文章摘要。列表省去中英文正文和代码练习配置，详情才返回这些字段。搜索检查标题、摘要、正文和标签，最多返回 30 条匹配及其摘录。
5. 直接打开 `/entry/{id}` 会进入 [PageController](src/main/java/com/ease2k/blog/controller/PageController.java)。[HtmlPageRenderer](src/main/java/com/ease2k/blog/service/HtmlPageRenderer.java) 用 CommonMark 渲染初始正文，生成 canonical、Open Graph 和初始 JSON。浏览器加载 [公开站点入口](frontend/client.tsx) 后，由 React 提供公式、代码高亮和交互。

### 从 Controller 到数据层

| 类 | 职责 |
| --- | --- |
| `ArticleService` | 处理文章详情、摘要、全文搜索和供 RSS 使用的文章列表 |
| `ArticleRepository` | 合并公开内容并维护按 ID 查询的快照 |
| `PageRenderer` | 将模板、初始正文、页面元数据和初始 JSON 拼成 HTML |
| `RatingsService` | 加载并定时更新 Codeforces 和 AtCoder Rating |
| `OwnerEntries` | 合并基础文章与 PostgreSQL 数据，管理草稿、发布和历史 |
| `OwnerSeries`、`OwnerMedia` | 管理专栏、图片文件和图片引用 |
| `OwnerAccessFilter`、`OwnerSecurity`、`OwnerSessions` | 校验工作台访问来源，在 Spring Security 中认证站主并检查写请求令牌 |
| `OwnerWorkbench` | 在同一事务中读取工作台列表、媒体列表和导出所需的数据 |

阅读代码时，可以从 Controller 的构造器找到注入的依赖，再在详情接口、Service 和 Repository 分别设置断点。工作台的 SQL 定义在 MyBatis Mapper 接口中，例如 [OwnerEntryMapper](src/main/java/com/ease2k/blog/owner/OwnerEntryMapper.java)。[OwnerDatabase](src/main/java/com/ease2k/blog/owner/OwnerDatabase.java) 负责连接 PostgreSQL，通过 `SqlSessionTemplate` 让 Mapper 参与 Spring 事务。写事务在读取修订版本之前取得 PostgreSQL 事务锁。

### 接口与实现

类之间通过构造器注入接口。以文章详情为例，Spring 将带 `@Service` 的 `ArticleServiceImpl` 注入 Controller，再将带 `@Repository` 的 `SnapshotArticleRepository` 注入 Service。调试时可以在接口声明处用 IDEA 的 **Go to Implementation** 跳到实现，再给具体方法打断点。

| 接口 | 当前实现 | 主要使用方 |
| --- | --- | --- |
| `ArticleService` | `ArticleServiceImpl` | 文章 API、HTML 页面、RSS、站点地图 |
| `ArticleRepository` | `SnapshotArticleRepository` | 文章服务、公开图片 |
| `PageRenderer` | `HtmlPageRenderer` | 页面 Controller |
| `RatingsService` | `RatingsServiceImpl` | Rating API |
| `OwnerEntries` | `SqliteOwnerEntries` | 工作台、公开快照、每日小记 |
| `OwnerSeries` | `SqliteOwnerSeries` | 工作台的专栏管理 |
| `OwnerMedia` | `SqliteOwnerMedia` | 工作台图片库、公开图片 |
| `OwnerSessions` | `SqliteOwnerSessions` | 工作台登录、会话校验 |

每日小记由 `DailyNoteInbox` 读取站主数据目录中的待处理 JSON，通过 `OwnerEntries` 保存草稿或发布。同日已有记录时会保留原内容。部署包附带 `daily-note-submit.sh`。

## 公开页面和接口

以下路由均使用 `GET`。

| 路径 | 内容 |
| --- | --- |
| `/`、`/articles`、`/daily`、`/series`、`/competitions` | 公开页面入口 |
| `/entry/{id}` | 含初始正文的文章页面 |
| `/api/index` | `{ entries, ready }`，用于公开列表的文章摘要 |
| `/api/entries/{id}` | `{ entry }`，含 Markdown 正文的文章详情 |
| `/api/search?q=关键词` | `{ entries }`，空查询返回空数组 |
| `/api/exports/articles` | `{ version, entries, ready }`，导出完整公开文章。旧地址 `/api/blog` 返回 308 跳转 |
| `/api/ratings` | Codeforces 与 AtCoder 的 Rating 和比赛历史 |
| `/feed.xml`、`/feed/technical.xml`、`/feed/series/{id}.xml` | 全站、技术文章和指定专栏 RSS，每份最多 50 项 |
| `/sitemap.xml`、`/robots.txt` | 搜索引擎索引配置 |
| `/media/{name}` | 公开内容图片 |
| `/healthz` | 运行检查，返回 `{"status":"UP"}` |

```bash
base=http://127.0.0.1:8089
curl -fsS "$base/healthz"
curl -fsS "$base/api/index"
curl -fsS "$base/api/entries/spring-web-request"
curl -fsS --get --data-urlencode 'q=PostgreSQL' "$base/api/search"
curl -fsS "$base/feed.xml"
```

公开版随包提供空的 Rating 快照。要查询自己的竞赛记录，可设置 `BLOG_RATINGS_LIVE=true`，并填写 `BLOG_CODEFORCES_HANDLE` 或 `BLOG_ATCODER_HANDLE`。生产配置默认开启在线刷新，只有填写账号的平台才会请求上游。刷新任务结束约 15 分钟后再次运行，上游异常时保留该平台上一次的数据并标记 `stale`。

## 文章来源和发布流程

- **基础文章**位于 [articles.json](src/main/resources/data/articles.json)。部署可用 `BLOG_CONTENT_DIRECTORY` 指向含有 `articles.json` 的独立目录，目录里的 JSON 会取代随包基础文章。加载发生在启动时，修改后需重启。离线导入旧站内容的命令在 [部署说明](deploy/DEPLOY.md)。
- **在线文章**存于 PostgreSQL 的 `entries` 表，正文和文章元数据保存在 `payload` 字段的 JSON 文本中。同 ID 的在线记录和回收站标记覆盖基础文章。公开查询只读公开快照，私有文章、样例和空正文不进入公开列表、搜索或 RSS。
- **编辑草稿**保存在 `working_copies` 中。编辑已发布文章时，自动保存的草稿可在工作台恢复，读者继续看到上一次发布的版本。点击发布后才更新公开正文。手动对已发布文章选择「撤下并存为草稿」会从公开站撤下该文章。回收站恢复后的记录为私有草稿。
- **图片**存于工作台的 `media/`。启用工作台时，公开图片路由会检查当前公开内容是否引用该图片。只读内容目录的导入图片放在 `public-media/`。
- **版本**用 `revision` 区分，防止旧编辑覆盖较新的修改。版本历史保留在 `history`，从历史载入内容并保存会写入新版本。历史正文会与公开状态、文章日期一起写入，供首次发布判断使用。

## 在本地使用站主工作台

从源码项目运行以下命令。已经解压发布包时，可以直接执行后两行。

```bash
./build-release.sh
./start-local.sh setup-owner
./start-local.sh
```

`setup-owner` 需要交互式终端，密码至少 12 个字符。已有 `owner.json` 且要沿用密码时，只需启动应用。再次执行 `setup-owner` 会重设凭据并使旧会话失效。打开 [本地工作台](http://127.0.0.1:8089/_owner) 后，可以新建一篇测试文章，依次确认草稿、发布、编辑历史和回收站。工作台的写请求由页面携带同源信息和 CSRF 令牌。

可以用示例文章走一遍完整流程：

1. 打开「一条 HTTP 请求如何找到文章」，修改正文并等待编辑草稿保存。另开一个标签页访问公开详情，仍会读取已发布的正文。
2. 返回工作台，点击「保存并更新文章」，刷新公开详情核对新正文。通过「修改记录」查看历史版本。
3. 将一篇本地测试文章移入回收站，确认公开详情返回 404。恢复为草稿后可以继续编辑，再按需发布。

## 常用配置

| 环境变量 | 作用 |
| --- | --- |
| `SERVER_ADDRESS`、`SERVER_PORT` | 默认监听 `127.0.0.1:8089` |
| `BLOG_CONTENT_DIRECTORY` | 外部基础文章目录，读取其 `articles.json` |
| `BLOG_EDITOR_DIRECTORY` | 配置后启用工作台，指向独立的私有数据目录 |
| `BLOG_DATABASE_URL`、`BLOG_DATABASE_USERNAME`、`BLOG_DATABASE_PASSWORD` | 连接外部 PostgreSQL 时使用。启动脚本默认连接项目自己的本地实例 |
| `BLOG_LOCAL_DATABASE_PORT`、`BLOG_LOCAL_DATA_DIRECTORY` | 内置本地实例的端口和数据目录，默认 `55432` 和 `$PWD/data` |
| `SPRING_PROFILES_ACTIVE=production` | 启用生产配置，默认开启 Rating 在线刷新 |
| `BLOG_PUBLIC_ORIGIN` | 规范站点来源。生产配置要求 HTTPS，用于 canonical、RSS 和站点地图 |
| `BLOG_OWNER_PROXY_SECRET` | 生产工作台与本机 HTTPS 代理共享的密钥 |
| `BLOG_RATINGS_LIVE` | 显式开关 Rating 在线刷新 |
| `BLOG_CODEFORCES_HANDLE`、`BLOG_ATCODER_HANDLE` | 查询对应平台所用的账号名，默认留空 |
| `BLOG_OFFLINE=1` | 本地构建脚本从缓存解析依赖 |
| `MAVEN_CMD` | 构建脚本使用的 Maven 可执行文件 |

## 构建、部署和排查

执行 `./build-release.sh` 会运行 TypeScript 类型检查、Vite 构建与 Maven 打包，生成 `target/release/blog.jar`、`target/release/ease2k-blog-springboot-postgresql.tar.gz` 和对应的压缩包校验文件。JAR 内包含前端构建产物。旧数据导入、备份与恢复见 [使用说明](deploy/DEPLOY.md)。

| 现象 | 检查方法 |
| --- | --- |
| `release version 27 not supported` | 检查 `java -version`、Maven 使用的 JDK 和 IDEA 的 Maven Runner JRE |
| 启动时提示缺少 `static/index.html` 或模板标记 | 先执行前端构建，然后重新执行 Maven `compile` 或打包 |
| `/_owner` 返回 404 | 本地用 `127.0.0.1` 或 `localhost` 访问，确认已配置编辑目录。生产检查 [HTTPS 代理配置](deploy/DEPLOY.md) |
| 列表还是旧文章 | 确认进程实际读取的内容目录，基础 JSON 修改后重启，同 ID 的在线记录会覆盖基础数据 |
| TypeScript 或 Vite 构建失败 | 检查 Node.js 版本，运行 `npm --prefix frontend ci` 和 `npm --prefix frontend run check` 定位问题 |

## 相关项目与资源

同一博客也有 [Node.js 公开版](https://github.com/ease2K/ease2k-blog-public)。平台图标及来源见 [资源署名](frontend/public/brands/CREDITS.md)。

作者保留原创代码和设计的相关权利。第三方依赖与品牌素材适用各自的许可证和使用说明。
