# Changelog

## 0.1.0-SNAPSHOT (2026-07-25)

| Area | Change |
|------|--------|
| build | Require sbt 2.0.3+; publish artifact suffix is now `_sbt2_3` |
| build | Upgrade sbt-pgp to 2.3.1, sbt-version-policy to 3.3.0 |
| plugins | Adapt Boot/War/Style/Snapshot plugins for sbt 2 API (VirtualFileRef, Def.uncached, string metadata keys) |
| style | `StylePlugin`: scan only current configuration source/resource dirs |
| boot | Reimplement `BootPlugin` on `UpdateReport` (compile/runtime main jars, exclude optional); drop `sbt2-compat` |
| util | `Utils` uses native `FileConverter` / `moduleIDStr` / `artifactStr` codecs |

**Downstream migration (sbt 1.x → 2.x):**

| Item | Action |
|------|--------|
| `project/build.properties` | Set `sbt.version=2.0.3` (or later) |
| `project/plugins.sbt` | Upgrade `sbt-beangle-build` to `0.1.0-SNAPSHOT` or later |
| `build.sbt` | Remove `ThisBuild /` prefix; change `url(...)` to `uri(...)` |
| Shell syntax | Use slash syntax (`Test/compile`); quote multi-commands: `sbt "clean ; compile"` |
| JDK | Require JDK 17+ |

**Breaking:** sbt 1.x is no longer supported by this plugin line. Stay on `0.0.x` if you remain on sbt 1.

## 0.0.23-SNAPSHOT and earlier

See git history. Requires sbt 1.x.
