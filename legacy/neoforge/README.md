# NeoForge (planned)

This directory reserves a home for a future NeoForge platform module. It is not
included in `settings.gradle.kts`; no NeoForge plugins or dependencies are resolved.

When requirements are defined, add a loader-specific entrypoint and adapters here,
reuse `common` with the same official Minecraft mappings, and package its output
in the NeoForge mod. Keep loader imports out of shared code. No platform interface
is needed by the current logging-only bootstrap.
