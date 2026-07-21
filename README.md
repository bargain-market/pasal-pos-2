# Pasal POS 2 — side-by-side PAX test build

**Pasal POS 2** is a full copy of the Pasal POS desktop application, rebranded so it
installs and runs **completely separately** from the production POS. Its purpose is PAX
POSLink 2 terminal certification / testing: you can install it on the same machine as the
live POS, point it at a PAX terminal or the BroadPOS simulator, and run test transactions
without touching real store data.

It is intentionally **isolated** from the production app:

| | Production "Pasal POS" | This "Pasal POS 2" |
|---|---|---|
| Windows install name | `POS-System` | `Pasal-POS-2` |
| Data dir (Windows) | `%APPDATA%/Pasal POS` | `%APPDATA%/Pasal POS 2` |
| Data dir (macOS) | `~/Library/Application Support/Pasal POS` | `…/Pasal POS 2` |
| Data dir (Linux) | `~/.pos-system` | `~/.pos-system-2` |
| Logs | `~/Documents/Pasal POS/…` | `~/Documents/Pasal POS 2/…` |
| Legacy DB migration | enabled | **disabled** (always starts empty) |
| Releases | `bargain-market/desktop-app-releases` | this repo's own Releases |

Because the data directory differs and the cross-install migration is disabled
(see `ConfigManager`), Pasal POS 2 **never reads or imports the production database** —
it comes up with its own empty database on first run.

> The Java package is still `com.pos.*` — that does not matter, because the two apps never
> run in the same JVM or share a data directory. Isolation is by install name + data dir.

## Build & run locally

```bash
./run.sh                 # mvn clean compile + javafx:run
# or
mvn clean package -DskipTests
```

PAX terminal address lives in `src/main/resources/application.properties`
(`pax.comm.host` / `pax.comm.port`) and is also editable in-app via **Settings → Hardware**.

## Push → GitHub Action → installer

`.github/workflows/release.yml` builds a **Windows `.exe` installer** on every push to
`main` (or a `v*` tag) and publishes it to **this repository's Releases** using the
built-in `GITHUB_TOKEN` — no PAT and no separate releases repo required.

To wire it up:

1. Create a GitHub repo (e.g. `bargain-market/pasal-pos-2`).
2. Push this project to it (see below).
3. The **Release Pasal POS 2** action runs automatically and, when it finishes, the
   installer appears under the repo's **Releases** (and as a downloadable workflow
   artifact on every run, including `main` and manual `workflow_dispatch` runs).

```bash
git remote add origin git@github.com:bargain-market/pasal-pos-2.git
git push -u origin main
```

Nothing else needs configuring — `permissions: contents: write` + `GITHUB_TOKEN` handle
the release. Cut a versioned release by pushing a tag, e.g. `git tag v2.0.0 && git push origin v2.0.0`.

## What changed vs. the production app

Only identity/isolation — no business logic was touched:

- `pom.xml`: `artifactId` → `pasal-pos-2`, `name` → `Pasal POS 2`, shade `finalName` → `pasal-pos-2-*`.
- `ConfigManager`: separate data dir + legacy DB migration disabled.
- `application.properties`: `app.name`, `app.version`, `app.github.repo` (auto-update stays **off**).
- Window titles / user-facing strings: `Pasal POS` → `Pasal POS 2`.
- `logback.xml`: log paths under `Pasal POS 2`.
- `.github/workflows/release.yml`: builds `Pasal-POS-2` and releases to this repo.
