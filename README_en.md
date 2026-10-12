# PixivDownloader Douyin plugin and community submission example

[简体中文](README.md)

Adds Douyin downloads, queue operations, a gallery and scheduled sources to PixivDownloader. The plugin ID is `douyin`, its version is `1.0.0-rc.4`, and its only production dependency is `io.github.sywyar.pixivdownloader:pixivdownload-sdk` declared in `pom.xml`. It requires runtime SDK `1.3` or later within the same major version.

This repository also serves as a complete community submission example, covering real plugin source, building, running, behavior declarations, candidate packages and submission for review. Being an example does not mean the plugin has passed community review.

The plugin runs inside the host with `host-process-full-trust` and uses the `process-restart` lifecycle. Community review, publisher signatures and execution modes do not provide an operating-system sandbox.

## Build

Install JDK 17, Node.js 24 or newer, and Git. Run from this directory:

```powershell
.\mvnw.cmd -B -ntp clean verify
Get-FileHash -Algorithm SHA256 .\target\pixivdownload-plugin-douyin-1.0.0-rc.4.jar
```

On Linux / macOS, use `./mvnw -B -ntp clean verify`. The wrapper pins Maven, and `pom.xml` declares the build dependencies. `verify` runs Java, JavaScript and packaged-JAR checks. After an online build, use `-o clean verify` to rebuild offline and compare hashes.

Local commands validate development builds. The SDK's `Plugin candidate` CI builds and tests in a pinned environment, compares offline rebuild hashes, and writes to this repository's `candidate-douyin` Draft. Later builds reuse that Draft and replace its assets. The wizard reads that candidate. JDK patch versions, operating-system line endings and file permissions can affect package bytes; an ordinary Windows build is not a substitute.

The JAR contains only Douyin classes and resources; the host supplies SDK and framework classes. Host JavaScript test inputs in `src/test/fixtures/workbench/` are excluded from the JAR. Their provenance and hashes are recorded in `source.json`.

Update both `pom.xml` and `src/main/resources/plugin.properties` when changing the plugin version. Maintain the SDK dependency separately. New prereleases use `alpha.N`, `beta.N` or `rc.N`; historical release identities remain unchanged. SemVer 2.0.0 is a reference; the actual host and release tooling define compatibility and ordering.

## Run and debug

The SDK development package used to run and debug the plugin must meet the SDK requirement in `plugin.properties` and match the compilation dependency in `pom.xml`.

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd exec:exec@sdk-stop
```

The first run downloads the full host archive pinned by `sdk-project.json` and verifies its SHA-256. Use the host setup page for initial configuration. Configuration, databases, downloads and logs live under this project's `.dev/`; runtime archives are cached under `~/.cache/pixivdownloader-sdk/`. For offline use, run `exec:exec@sdk-prepare` beforehand as well as caching Maven dependencies.

In IntelliJ IDEA, open `pom.xml`, select `Developer Mode` and use Run or Debug, with source breakpoints in `DouyinPlugin.java`. The native Application configuration loads the current `target/classes`; the test-source launcher is excluded from the JAR. VS Code and Eclipse configurations in `.vscode/` and `eclipse/` run and remotely debug the packaged plugin.

To run the built package without opening the GUI:

```powershell
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar run . target/pixivdownload-plugin-douyin-1.0.0-rc.4.jar --no-gui
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar stop .
```

`tools/`, `sdk-project.json`, `contracts/community/v1/` and the Wrapper come from the same SDK release. When upgrading the SDK, update matching files and the SDK dependency in `pom.xml` together, then revalidate the plugin. Candidate workflow maintenance fixes can be applied separately while preserving compatibility with the candidate data and tools. See the [SDK Javadocs](https://sywyar.github.io/PixivDownloader-Plugin-SDK/).

## Private database

`DouyinPlugin.privateSchema()` declares the work, file and source-relation tables. `privateStorageMigrations()` declares the one-time conversion that fills known source relations for legacy works. The host initializes the structure and runs conversions before creating business beans, then binds storage to the `douyin` owner. `DouyinHistoryMapper` uses `PluginDatabase` queries, writes, conflict rules and transactions without SQL or database-specific branches.

The SQLite provider retains `data/douyin/plugin.db`; the PostgreSQL provider maps an independent owner schema. The host and provider manage physical locations and connections. Work, file and relation records commit together. Repeated source discoveries retain the first known order and earliest discovery time. Paths keep their `{douyin}` encoding, and credentials stay out of these tables.

Upgrading a legacy SQLite database fills known source relations while preserving existing ones. Switching databases requires the host's offline migration and verification; installing the new plugin does not migrate data. Failed migrations preserve the source. Do not delete private databases or bypass receipt checks to force a switch. Beans can be assembled when storage is unavailable, but reads and writes fail explicitly instead of returning an apparently empty history.

Other plugins can follow these declarations and the mapper's neutral operations. Each plugin declares changes in business meaning. Adding a provider that implements the same storage contract requires no new dialect branches in the business plugin.

### Storage tests

Default `verify` uses an independent fixture backed by real SQLite. To test a production provider, supply its plugin JAR. Tests load it through the public SDK; the provider and JDBC drivers are not bundled in Douyin:

```powershell
mvn test '-Dtest=DouyinHistoryServiceTest,DouyinNeutralStorageTest,DouyinStorageMigrationTest' `
  "-Ddouyin.test.provider.jar=$sqliteProviderJar" `
  '-Ddouyin.test.provider.class=top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin'
```

For PostgreSQL, set `DOUYIN_TEST_POSTGRES_URL`, `DOUYIN_TEST_POSTGRES_USER` and `DOUYIN_TEST_POSTGRES_PASSWORD`. Use an isolated test database's base URL without query parameters. Tests create and drop random schemas. Run:

```powershell
mvn test '-Dtest=DouyinHistoryServiceTest,DouyinNeutralStorageTest,DouyinStorageMigrationTest#providerMigration' `
  "-Ddouyin.test.provider.jar=$postgresProviderJar" `
  '-Ddouyin.test.provider.class=top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin' `
  "-Ddouyin.test.sqlite.jar=$sqliteProviderJar" `
  "-Ddouyin.test.migration-target.jar=$postgresProviderJar" `
  '-Ddouyin.test.migration-target.class=top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin'
```

Without the provider JAR, the provider migration test reports a skip. That is not evidence that production-provider migration passed.

## Declared behavior and data

The descriptor declares network access, file reads, writes and deletion, credential access and host data access. The plugin uses stable SDK interfaces for Douyin API, short-link and media requests, using configured Douyin credentials. It reads plugin settings, writes downloads, cleans temporary files and consumes host identity and task context. Configuration and database access use the plugin's owner scope. Declarations describe behavior; they do not grant permissions or prove complete scan coverage.

Keep account cookies, private keys, `.dev/` and downloads out of Git. The development host uses separate state and does not migrate an existing installation's data.

## Submit from the source repository

Push the source to the default branch, wait for every `Plugin candidate` job to pass, then run the wizard using the [community submission guide](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README_en.md#submissions-and-version-management). Keep your working tree clean and on the same commit as CI. You do not need to download artifacts, create a Release or upload packages yourself. That guide maintains the submission command, cache recovery and general steps.

Use these values in the wizard:

| Field | Selection or expected value |
| --- | --- |
| Project | This repository's root directory |
| Build profile | Automatically detected as Maven (`maven-java17-v1`) |
| Package | Read from the CI candidate for this source commit |
| Plugin ID / version | `douyin` / `1.0.0-rc.4` |
| Source repository Release | CI reuses one Draft and replaces its assets; after confirmation, the wizard saves a fixed pre-release that later builds do not overwrite |

Choose your own GitHub publisher identity and confirm the behavior declarations and license against the plugin.

## Use the example for your own plugin

| File or directory | What it demonstrates |
| --- | --- |
| `pom.xml` | One provided SDK dependency, tests, a thin JAR and fixed build inputs |
| `src/main/resources/plugin.properties` | Plugin identity, SDK requirement, execution mode, lifecycle and behavior declarations |
| `src/main/java/top/sywyar/pixivdownload/douyin/` | PF4J entry point, stable API contributions, configuration, HTTP, queues and scheduled sources |
| `src/main/resources/static/`, `i18n/` | Plugin pages, static resources and localized text |
| `src/test/` | Business regression, host dependency boundaries and packaged-artifact checks |
| `.github/workflows/candidate.yml` | SDK candidate builds, offline rebuild comparison and source Draft archival |

Fork and run it in a separate development environment to learn. To publish a new plugin, change the plugin ID, Maven artifactId, Java package and entry point, routes and resource URLs, i18n namespace, and corresponding tests and IDE references. Update behavior declarations to match your code. Retain the license and original notices when reusing code; do not copy publisher identities or keys. Improvements to Douyin itself should go through a source PR so its publisher can submit a new version.

`sdk-project.json` and its matching tools identify the SDK environment, not your publisher account. Renaming your plugin does not justify rewriting those identities. The wizard generates submission JSON, signatures and hashes from the actual source and package. This example supplies no publisher records or private keys for reuse.

## License

This project retains [AGPL-3.0](LICENSE). Its source comes from the Douyin module in [PixivDownloader](https://github.com/Sywyar/PixivDownloader). Bundled SDK tools and test inputs retain their original license notices.
