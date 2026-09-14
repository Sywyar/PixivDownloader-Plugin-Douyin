# Workbench JavaScript test inputs

These six files are copied from the PixivDownloader commit recorded in [source.json](source.json). They exercise Douyin's browser modules against the queue and scheduled-source registration runtime used by that host.

They are test inputs only. Maven does not include this directory in the plugin JAR. The plugin uses the running host's registration context; it does not ship a private workbench runtime.

The files retain the source project's AGPL-3.0 license. When updating them, copy all six files from one reviewed host commit and update their SHA-256 values in `source.json`.
