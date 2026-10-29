# Changelog

## [Unreleased]

### Bug Fixes

- 修复抖音 Web 请求缺少 UIFID 与有效签名时被拒绝，导致计划无法取得账号作品的问题。

### Features

- 抖音历史、文件明细和来源关系支持通过宿主数据库提供者使用 SQLite 或 PostgreSQL，并参与停机迁移及核验；需要 SDK 1.3，升级不会自动切换数据库。
