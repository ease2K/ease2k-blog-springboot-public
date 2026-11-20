# ease2k 博客 · Spring Boot 版

用 JDK 27、Spring Boot 4.1.2、React 和 SQLite 实现的个人博客。仓库提供三篇可本地运行的示例，用来练习文章查询、Markdown 渲染、站内搜索、RSS 和站主工作台。

## 在本机启动

准备 JDK 27、Maven 和 Node.js 22.13 或以上版本，在项目根目录运行：

```bash
./run-local.sh
```

打开 [本地首页](http://127.0.0.1:8088/) 和 [文章示例](http://127.0.0.1:8088/entry/spring-web-request)。没有把 Maven 加到 PATH 时，脚本也会尝试使用 IntelliJ IDEA 自带的 Maven。

## 在 IntelliJ IDEA 中调试

1. 打开根目录的 `pom.xml`。将 Project SDK 和 Maven Runner JRE 都设为 JDK 27。
2. 执行 `npm --prefix frontend ci` 和 `npm --prefix frontend run build`。修改前端后，重新执行构建。
3. 执行 Maven 的 `compile`，把前端构建产物复制到 Java 运行目录。
4. 打开 [BlogApplication.java](src/main/java/com/ease2k/blog/BlogApplication.java)，在 `main()` 左侧点击 Debug。可以在 `ArticleController.entry()` 上打断点，然后请求详情接口。

```bash
curl http://127.0.0.1:8088/api/index
curl http://127.0.0.1:8088/api/entries/spring-web-request
curl --get --data-urlencode 'q=SQLite' http://127.0.0.1:8088/api/search
```

阅读路径从 [Controller](src/main/java/com/ease2k/blog/controller/ArticleController.java) 开始，继续到 [Service](src/main/java/com/ease2k/blog/service/ArticleService.java) 和 [Repository](src/main/java/com/ease2k/blog/repository/ArticleRepository.java)。初始内容来自 [articles.json](src/main/resources/data/articles.json)。

## 练习草稿与发布

先用 `./build-release.sh` 打包，再解出 JAR 并给本机练习目录设置密码：

```bash
mkdir -p target/learning
tar -xzf target/release/ease2k-blog-springboot.tar.gz -C target/learning
java -jar target/learning/blog.jar setup-owner "$PWD/editor"
BLOG_EDITOR_DIRECTORY="$PWD/editor" ./run-local.sh
```

停止之前启动的实例后再运行最后一条命令。打开 [本地站主入口](http://127.0.0.1:8088/_owner)，选一篇示例文章，分别试一下保存草稿和发布，然后刷新公开详情页。编辑数据位于被 Git 忽略的 `editor/`。

更多配置见 [部署说明](deploy/DEPLOY.md)。如需查询自己的竞赛记录，可启用 `BLOG_RATINGS_LIVE=true`，并设置 `BLOG_CODEFORCES_HANDLE` 或 `BLOG_ATCODER_HANDLE`。

## 对照 Node.js 版

同一博客也有 Node.js 后端。 [Node.js 版](https://github.com/ease2K/ease2k-blog-public)

作者保留原创代码和设计的相关权利。第三方依赖与品牌素材适用各自的许可证和使用说明。
