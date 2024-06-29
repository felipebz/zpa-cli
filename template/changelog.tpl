## Changelog

{{changelogChanges}}

## Highlights

### Support for custom plugins

ZPA CLI now supports the same custom plugins as the ZPA Plugin for SonarQube. You can create a plugin by following the [plugin development guide](https://github.com/felipebz/zpa/wiki/Create-a-plugin-with-custom-rules).

To use a custom plugin, simply add the JAR file to the `plugins` directory.

## Binaries

### 🌟 Universal

This distribution requires an external Java runtime.

* {{#f_release_download_url}}zpa-cli-{{projectVersion}}.zip{{/f_release_download_url}} (requires Java 17+)

### ☕️ Bundled Java Runtimes

These binaries provide their own Java runtime.

|Platform | Intel | Arm |
| ------- | ----- | --- |
| MacOS   | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-osx-x86_64.tar.gz{{/f_release_download_url}} | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-osx-aarch_64.tar.gz{{/f_release_download_url}} |
| Linux (glibc) | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-linux-x86_64.tar.gz{{/f_release_download_url}} | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-linux-aarch_64.tar.gz{{/f_release_download_url}} |
| Alpine Linux (musl) | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-linux_musl-x86_64.tar.gz{{/f_release_download_url}} | |
| Windows | {{#f_release_download_url}}zpa-cli-{{projectVersion}}-windows-x86_64.zip{{/f_release_download_url}} | |

