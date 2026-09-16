# PixivDownloader Douyin 插件与社区投稿示例

[English](README_en.md)

为 PixivDownloader 提供抖音作品下载、队列操作、画廊和计划来源。插件 ID 为 `douyin`，版本为 `1.0.0-rc.1`，仅通过 `pom.xml` 声明的已发布 `io.github.sywyar.pixivdownloader:pixivdownload-sdk` 取得生产依赖，无需克隆主程序源码。

本仓库也作为完整社区投稿示例：开发者可以从实际插件源码开始，学习构建、运行、能力声明、生成候选包和提交审核。示例身份不表示插件已经通过社区审核。

插件在宿主进程中以 `host-process-full-trust` 运行，采用 `process-restart` 生命周期。社区审核、开发者签名与执行模式不构成操作系统沙箱。

## 构建

安装 JDK 17、Node.js 24 或更高版本和 Git，在本目录运行：

```powershell
.\mvnw.cmd -B -ntp clean verify
Get-FileHash -Algorithm SHA256 .\target\pixivdownload-plugin-douyin-1.0.0-rc.1.jar
```

Linux / macOS 使用 `./mvnw -B -ntp clean verify`。Wrapper 固定 Maven 版本；首次构建从 Maven Central 下载依赖。`verify` 执行 Java、JavaScript 和最终 JAR 检查。在线构建后可执行 `-o clean verify` 离线重建并比较摘要。

本地命令用于开发验证。投稿候选由 SDK 提供的 `Plugin candidate` CI 在固定环境中构建、测试并比较离线重建摘要，再写入本仓库的 `candidate-douyin` 草稿；后续构建复用此草稿并覆盖产物。向导读取该候选；JDK 补丁版本、操作系统换行和文件权限可能改变包字节，普通 Windows 构建包不能代替它。

产物只包含 Douyin 类和资源，SDK 与框架类由宿主提供。`src/test/fixtures/workbench/` 中的宿主 JavaScript 测试输入不进入 JAR，来源和摘要见该目录的 `source.json`。

修改插件版本时同步更新 `pom.xml` 和 `src/main/resources/plugin.properties`。插件版本与 SDK 依赖分别维护，新预发布后缀采用 `alpha.N`、`beta.N`、`rc.N`，历史发行身份不改写。SemVer 2.0.0 仅作参考，兼容性与排序以实际宿主及发布链路为准。

## 运行与调试

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd exec:exec@sdk-stop
```

首次运行会下载 `sdk-project.json` 固定的完整宿主附件并检查 SHA-256；首次配置使用宿主 setup 页面。配置、数据库、下载和日志保存在本工程 `.dev/`，运行包缓存在 `~/.cache/pixivdownloader-sdk/`。离线运行还需提前执行 `exec:exec@sdk-prepare`，仅缓存 Maven 依赖不够。

IntelliJ IDEA 打开 `pom.xml` 后选择 `Developer Mode`，即可 Run / Debug 并在 `DouyinPlugin.java` 中设置断点。它使用原生 Application 加载当前 `target/classes`，测试源码启动器不进入 JAR。VS Code 和 Eclipse 的共享配置位于 `.vscode/` 和 `eclipse/`，使用打包产物运行和远程调试。

只运行已构建包且不打开 GUI：

```powershell
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar run . target/pixivdownload-plugin-douyin-1.0.0-rc.1.jar --no-gui
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar stop .
```

`tools/`、`sdk-project.json`、`contracts/community/v1/` 和 Wrapper 来自同一 SDK 发行物。升级 SDK 时一并更新匹配文件与 `pom.xml` 的 SDK 依赖，再重新验证插件。候选 workflow 的维护修复可单独同步，须保持候选数据与工具兼容。[SDK Javadocs](https://sywyar.github.io/PixivDownloader-Plugin-SDK/) 提供 API 文档。

## 能力声明与数据

描述符声明网络访问、文件读取、写入、删除、凭证访问及宿主数据访问。插件经稳定 SDK 接口请求抖音 API、短链接和媒体地址，使用配置的抖音凭证；读取插件设置、写入下载文件、清理临时文件，并消费宿主身份和任务上下文。配置与数据库使用插件自己的 owner 作用域。声明描述行为，不授予权限，也不保证扫描覆盖全部行为。

不要提交账号 Cookie、私钥、`.dev/` 或下载文件。开发环境使用独立状态，不会替您迁移现有安装的数据。

## 从源码仓库投稿

推送源码到默认分支，等待 `Plugin candidate` 的全部任务通过，再按[社区投稿教程](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README.md#投稿与版本管理)运行向导。保持本地工作树干净且与 CI 的提交一致；无需手动下载附件、创建 Release 或上传包。投稿命令、缓存恢复和通用步骤统一以该教程为准。

向导中的选项如下：

| 项目 | 选择或核对的值 |
| --- | --- |
| 工程 | 当前仓库根目录 |
| 构建配置 | 自动识别为 Maven（`maven-java17-v1`） |
| 插件包 | 从本次源码提交的 CI 候选读取 |
| 插件 ID / 版本 | `douyin` / `1.0.0-rc.1` |
| 源码仓库 Release | CI 复用一个 Draft 并覆盖产物；确认投稿后，向导另存固定 Pre-release，后续构建不覆盖已投稿的包 |

发布者选择自己的 GitHub 身份，按实际行为确认能力声明和许可证。

## 作为示例开发自己的插件

| 文件或目录 | 可以参考的内容 |
| --- | --- |
| `pom.xml` | 单个 provided SDK 依赖、测试、薄 JAR 和固定构建输入 |
| `src/main/resources/plugin.properties` | 插件身份、SDK 要求、执行模式、生命周期和能力声明 |
| `src/main/java/top/sywyar/pixivdownload/douyin/` | PF4J 入口、稳定 API 贡献、插件配置、HTTP、队列和计划来源 |
| `src/main/resources/static/`、`i18n/` | 插件页面、静态资源及本地化文案 |
| `src/test/` | 业务回归、宿主依赖边界和最终产物检查 |
| `.github/workflows/candidate.yml` | SDK 候选构建、离线重建比对与源码 Draft 归档 |

学习时可以直接 fork 并在独立开发环境运行。要发布自己的新插件，需要更换 `plugin.id`、Maven artifactId、Java 包与入口类、路由和资源地址、i18n namespace，以及对应测试和 IDE 引用；同时按实际行为更新能力声明。沿用示例代码时保留许可证和原始许可声明，不要照抄发布者身份或密钥。向现有 Douyin 提交改进，应通过源码 PR，由其发布者提交新版本。

`sdk-project.json` 与配套 SDK 工具记录开发环境身份，不是您的发布者身份，不能因改插件名称而随意改写。投稿 JSON、发布者签名和摘要由向导从实际源码及包生成；本仓库不提供可直接冒用的发布者记录或私钥。

## 许可证

沿用 [AGPL-3.0](LICENSE)。源码基于 [PixivDownloader](https://github.com/Sywyar/PixivDownloader) 的 Douyin 模块；SDK 工具和测试输入保留原始许可声明。
