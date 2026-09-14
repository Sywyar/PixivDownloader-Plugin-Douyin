# PixivDownloader Douyin plugin and community submission example

[简体中文](README.md)

Adds Douyin downloads, queue operations, a gallery and scheduled sources to PixivDownloader. The plugin ID is `douyin`, its version is `1.0.0-rc.1`, and its only production dependency is the published `io.github.sywyar.pixivdownloader:pixivdownload-sdk:1.0.0-rc6`. No host source checkout is required.

This repository also serves as a complete community submission example, covering real plugin source, building, running, behavior declarations, candidate packages and submission for review. Being an example does not mean the plugin has passed community review.

The plugin runs inside the host with `host-process-full-trust` and uses the `process-restart` lifecycle. Community review, publisher signatures and execution modes do not provide an operating-system sandbox.

## Build

Install JDK 17, Node.js 24 or newer, and Git. Run from this directory:

```powershell
.\mvnw.cmd -B -ntp clean verify
Get-FileHash -Algorithm SHA256 .\target\pixivdownload-plugin-douyin-1.0.0-rc.1.jar
```

On Linux / macOS, use `sh ./mvnw -B -ntp clean verify`. The wrapper pins Maven; the first build downloads dependencies from Maven Central. `verify` runs Java, JavaScript and packaged-JAR checks. After an online build, use `-o clean verify` to rebuild offline and compare hashes.

Local commands validate development builds. For submission, use the `douyin-candidate` artifact from `Verify plugin` CI. The workflow pins the community build image digest, Maven 3.9.11 and Node.js 24.21.0, and compares two builds. JDK patch versions, operating-system line endings and file permissions affect package bytes; an ordinary Windows build is not a substitute for this candidate.

The JAR contains only Douyin classes and resources; the host supplies SDK and framework classes. Host JavaScript test inputs in `src/test/fixtures/workbench/` are excluded from the JAR. Their provenance and hashes are recorded in `source.json`.

Update both `pom.xml` and `src/main/resources/plugin.properties` when changing the plugin version. Maintain the SDK dependency separately. New prereleases use `alpha.N`, `beta.N` or `rc.N`. The published SDK identity `1.0.0-rc6` remains unchanged; `1.0.0-rc.6` is not an alias. SemVer 2.0.0 is a reference; the actual host and release tooling define compatibility and ordering.

## Run and debug

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd exec:exec@sdk-stop
```

The first run downloads the full host archive pinned by `sdk-project.json` and verifies its SHA-256. Use the host setup page for initial configuration. Configuration, databases, downloads and logs live under this project's `.dev/`; runtime archives are cached under `~/.cache/pixivdownloader-sdk/`. For offline use, run `exec:exec@sdk-prepare` beforehand as well as caching Maven dependencies.

In IntelliJ IDEA, open `pom.xml`, select `Developer Mode` and use Run or Debug, with source breakpoints in `DouyinPlugin.java`. The native Application configuration loads the current `target/classes`; the test-source launcher is excluded from the JAR. VS Code and Eclipse configurations in `.vscode/` and `eclipse/` run and remotely debug the packaged plugin.

To run the built package without opening the GUI:

```powershell
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar run . target/pixivdownload-plugin-douyin-1.0.0-rc.1.jar --no-gui
java '-Dfile.encoding=UTF-8' -jar tools/sdk-tools.jar stop .
```

`tools/sdk-tools.jar`, `sdk-project.json`, `tools/community-contract.json` and `contracts/community/v1/` come from the same SDK release. Upgrade matching files together and revalidate the plugin. See the [SDK Javadocs](https://sywyar.github.io/PixivDownloader-Plugin-SDK/).

## Declared behavior and data

The descriptor declares network access, file reads, writes and deletion, credential access and host data access. The plugin uses stable SDK interfaces for Douyin API, short-link and media requests, using configured Douyin credentials. It reads plugin settings, writes downloads, cleans temporary files and consumes host identity and task context. Configuration and database access use the plugin's owner scope. Declarations describe behavior; they do not grant permissions or prove complete scan coverage.

Keep account cookies, private keys, `.dev/` and downloads out of Git. The development host uses separate state and does not migrate an existing installation's data.

## Submit from the source repository

Follow the [community submission guide](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README_en.md#submissions-and-version-management) to prepare public source, upload the package and run the wizard. That guide maintains the submission command and general steps.

Use the candidate from `Verify plugin` CI for the source commit. Download `douyin-candidate` and extract its JAR and `SHA256SUMS` into this project's `target/`. Check that the CI head SHA matches the local commit and that the JAR hash matches the checksum file, then upload that JAR. Do not overwrite it with a local development build before submitting.

Use these values in the wizard:

| Field | Selection or expected value |
| --- | --- |
| Project | This repository's root directory |
| Build profile | `maven-java17-v1` |
| Package | `target/pixivdownload-plugin-douyin-1.0.0-rc.1.jar` |
| Plugin ID / version | `douyin` / `1.0.0-rc.1` |
| Source repository Release | Public Pre-release, for example under tag `v1.0.0-rc.1` |

Choose your own GitHub publisher identity and confirm the behavior declarations and license against the plugin.

## Use the example for your own plugin

| File or directory | What it demonstrates |
| --- | --- |
| `pom.xml` | One provided SDK dependency, tests, a thin JAR and fixed build inputs |
| `src/main/resources/plugin.properties` | Plugin identity, SDK requirement, execution mode, lifecycle and behavior declarations |
| `src/main/java/top/sywyar/pixivdownload/douyin/` | PF4J entry point, stable API contributions, configuration, HTTP, queues and scheduled sources |
| `src/main/resources/static/`, `i18n/` | Plugin pages, static resources and localized text |
| `src/test/` | Business regression, host dependency boundaries and packaged-artifact checks |
| `.github/workflows/verify.yml` | The fixed community build environment, rebuild comparison and candidate artifacts |

Fork and run it in a separate development environment to learn. To publish a new plugin, change the plugin ID, Maven artifactId, Java package and entry point, routes and resource URLs, i18n namespace, and corresponding tests and IDE references. Update behavior declarations to match your code. Retain the license and original notices when reusing code; do not copy publisher identities or keys. Improvements to Douyin itself should go through a source PR so its publisher can submit a new version.

`sdk-project.json` and its matching tools identify the SDK environment, not your publisher account. Renaming your plugin does not justify rewriting those identities. The wizard generates submission JSON, signatures and hashes from the actual source and package. This example supplies no publisher records or private keys for reuse.

## License

This project retains [AGPL-3.0](LICENSE). Its source comes from the Douyin module in [PixivDownloader](https://github.com/Sywyar/PixivDownloader). Bundled SDK tools and test inputs retain their original license notices.
