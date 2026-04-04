# Dispatch — CLAUDE.md

> JavaFX homelab SSH manager + Docker control panel. Native desktop app: VS Code Remote-SSH +
> tmux + Portainer in one window.

---

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Java 21 LTS | Virtual threads (Loom) for all I/O |
| UI Framework | JavaFX 21 LTS | FXML for layout, CSS for styling |
| Build Tool | Gradle 8+ (Kotlin DSL) | `build.gradle.kts` |
| SSH Client | Apache MINA SSHD | Prefer over JSch |
| Terminal | JediTerm (standalone) | Embedded terminal in SSH tabs |
| Docker API | docker-java | Via SSH tunnel to `/var/run/docker.sock` |
| UI Theme | AtlantaFX | Base dark theme + custom CSS overrides |
| Font | JetBrains Mono | Everywhere — UI, terminals, tables |
| Storage | SQLite via `sqlite-jdbc` | Host profiles, session history, settings |
| JSON | Jackson | Docker API responses, config files |
| Reactive | RxJava 3 | Log streaming, live stats |
| Logging | SLF4J + Logback | Structured logging |
| Secrets | Java KeyStore | SSH passwords/passphrases — never in SQLite |

---

## Architecture

Modules (one per directory, one responsibility per class):

- `core/model/` — Host, Session, Container, etc.
- `core/config/` — AppConfig, constants
- `ssh/` — SshService, SshSession, TunnelService
- `ssh/terminal/` — TerminalController (JediTerm integration)
- `docker/` — DockerService, DockerDetector, models
- `storage/` — DatabaseManager, HostRepository, SessionRepository
- `ui/` — MainController + per-feature controllers (thin, delegate to services)

**Dependency direction (enforced):**
```
ui → service → model
ui → storage
service → storage / model
```
`model` and `storage` must never import from `ui` or services.

**SQLite tables:** `hosts`, `sessions`, `app_settings`
**Sensitive data:** Java KeyStore at `~/.dispatch/dispatch.jks`
**DB path:** `~/.dispatch/dispatch.db`

---

## UI / UX — Theme

### Color Palette (from `dispatch-dark.css`)

```css
/* Backgrounds */
--bg:      #161616;   /* main background */
--bg2:     #1c1c1c;   /* sidebar */
--bg3:     #222222;   /* panels, inputs */
--panel:   rgba(255,255,255,0.02);

/* Borders */
--border:  rgba(255,255,255,0.08);
--border2: rgba(255,255,255,0.14);

/* Text */
--fg:      #e2e2e2;   /* primary text */
--fg2:     #909090;   /* secondary / dim */
--fg3:     #505050;   /* disabled / placeholder */

/* Accents */
--accent:  #7eb8ba;   /* primary accent — focus, links, active states */
--accent2: #a0a0b0;   /* secondary accent */
--cyan:    #8ec8c8;

/* Semantic */
--green:   #85c491;   /* running, connected, success */
--red:     #d47e7e;   /* stopped, error, failed */
--yellow:  #d4b07a;   /* warning, paused */

/* Font */
--mono: 'JetBrains Mono', monospace;
```

**Badge colors (containers):**
- Running: `rgba(133,196,145,0.12)` bg · `--green` text · `rgba(133,196,145,0.25)` border
- Stopped: `rgba(212,126,126,0.12)` bg · `--red` text · `rgba(212,126,126,0.25)` border
- Paused:  `rgba(212,176,122,0.12)` bg · `--yellow` text · `rgba(212,176,122,0.25)` border

**Active/hover states:** `rgba(95,158,160,0.07)` bg for selected host/container rows.

### Layout
- Left sidebar (host list) + main TabPane + optional right Docker panel
- All long-running ops show progress — never block FX Application Thread
- Terminal: `--bg` background, `--fg` text, JetBrains Mono, seamless with theme

---

## Connection State

```
DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED
                                ↓
                           LOST (unexpected drop)
```

On `LOST`: tab shows `⚠ disconnected` (`--yellow`), terminal read-only, "Reconnect" button overlay.
No auto-reconnect — user-initiated only.

**Timeouts:** connect 10s · keep-alive 30s · max keep-alive failures 3

---

## Development Rules

- **Virtual threads** for all blocking I/O: `Thread.ofVirtual()`
- **RxJava 3** for streams — subscribe on IO scheduler, observe on FX thread via `JavaFxScheduler`
- **Never block FX Application Thread** — use `Platform.runLater()` or `Task<>`
- **Docker access via SSH tunnel** to `/var/run/docker.sock` — never expose TCP 2375/2376
- **Never store secrets in SQLite** — Java KeyStore only
- **Use `Path`/`Paths.get()`** — no hardcoded `/` or `\`; SSH key default via `System.getProperty("user.home")`
- **Logging:** SLF4J at DEBUG/INFO/WARN/ERROR. Always pass exception object to `log.error()`. Never `System.out.println`.
- **Exceptions:** never swallow. Define module-specific types (`DispatchConnectionException`, `DockerServiceException`).
- **Class size:** ~150 lines max. One responsibility per file.
- **Method size:** ~30 lines max. Name private methods clearly instead of inline comments.
- **Comments:** explain *why*, not *what*. Javadoc on every public class and method.

---

## Workflow & Collaboration Rules

### One module at a time

A module = one self-contained slice (e.g. `storage/`, `ssh/` core, JediTerm integration,
`docker/` service, one UI panel). Never implement more than one module per turn.

### Mandatory deliverables per module

Every module response must include all four sections:

**1. 📦 What this does** — plain-language explanation + public API (key classes/methods)
**2. 🔗 How it connects** — dependencies in / dependencies out / position in architecture
**3. 🧪 How to test manually** — step-by-step verification (main harness, unit test, or UI steps)
**4. ➡️ What comes next** — short list of logical next modules. Do NOT implement them.

### Stop and wait

After delivering a module + four sections: **stop completely**. Wait for explicit approval.

Acceptable signals: "ok", "wygląda dobrze", "zatwierdzone", "go ahead", "następny"

### Architectural decisions

When two approaches are roughly equal — do NOT pick silently. Present:

```
⚖️ Decision needed: <topic>

Option A — <name>
  ✅ pro  ❌ con

Option B — <name>
  ✅ pro  ❌ con

→ Recommendation: Option A
```

Wait for developer choice before writing code.

### Never
- Write code for multiple modules in one response
- Continue without explicit approval
- Make significant architectural decisions silently
- Refactor unrelated code while implementing a module
- Add unapproved dependencies

---

## Git & Versioning

**Branching:** `main` (stable only) + `feat/<module-name>` per module. Work on `feat/` only.

**Commits (Conventional Commits):** `type(scope): short description`
Types: `feat` · `fix` · `refactor` · `style` · `test` · `docs` · `chore`

After approval, suggest exact commit message:
> 💾 Suggested commit: `feat(storage): add DatabaseManager and HostRepository`

After approval, suggest merge command (never run it):
```
git checkout main && git merge --no-ff feat/<module-name> && git push origin main
```

**SemVer:** starting at `0.1.0`. Version defined once in `build.gradle.kts`.
Suggest CHANGELOG entry alongside commit message.

**Formatter:** google-java-format via Spotless. Run `./gradlew spotlessApply` before commits.