# Dispatch — CLAUDE.md

> JavaFX homelab management application. Acts as a central control panel for SSH sessions,
> terminal multiplexing, and Docker management across all homelab hosts.

---

## Project Overview

**Dispatch** is a desktop application written in Java 21 with JavaFX 21, targeting a single
developer's self-hosted homelab. Think of it as a combination of VS Code Remote-SSH,
tmux, and Portainer — but as a native desktop app.

**Primary use cases:**
- Connect to homelab hosts via SSH and manage multiple terminal sessions in one window
- Auto-detect Docker on connected hosts and manage containers (start/stop/logs/exec/stats)
- Persist host profiles and connection history locally via SQLite

---

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Java 21 LTS | Use virtual threads (Project Loom) for all I/O |
| UI Framework | JavaFX 21 LTS | FXML for layout, CSS for styling |
| Build Tool | Gradle 8+ (Kotlin DSL) | `build.gradle.kts` |
| SSH Client | Apache MINA SSHD | Prefer over JSch — actively maintained |
| Terminal Emulator | JediTerm (standalone) | For embedded terminal in SSH sessions |
| Docker API | docker-java | Connect via SSH tunnel to `/var/run/docker.sock` |
| UI Theme | AtlantaFX | Dark theme — custom Challenger Deep palette on top of base theme |
| Font | JetBrains Mono | Used everywhere — UI labels, terminals, code, tables |
| Local Storage | SQLite via `sqlite-jdbc` | Host profiles, session history, app settings |
| JSON | Jackson | Parsing Docker API responses, config files |
| Reactive | RxJava 3 | Live container stats, log streaming, SSH output |
| Logging | SLF4J + Logback | Structured logging, useful for SSH/Docker debug |
| Secrets | Java KeyStore (built-in) | Store SSH passwords and keys encrypted |

---

## Architecture

```
dispatch/
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md
└── src/
    └── main/
        ├── java/
        │   └── dev/dispatch/
        │       ├── App.java                  # JavaFX entry point
        │       ├── core/
        │       │   ├── model/                # Host, Session, Container, etc.
        │       │   └── config/               # AppConfig, constants
        │       ├── ssh/
        │       │   ├── SshService.java        # Connect, disconnect, exec
        │       │   ├── SshSession.java        # Wraps a single SSH connection
        │       │   ├── TunnelService.java     # Port forwarding
        │       │   └── terminal/
        │       │       └── TerminalController.java  # JediTerm integration
        │       ├── docker/
        │       │   ├── DockerService.java     # docker-java client wrapper
        │       │   ├── DockerDetector.java    # Auto-detect Docker on host via SSH
        │       │   └── model/                # ContainerInfo, ImageInfo, etc.
        │       ├── storage/
        │       │   ├── DatabaseManager.java  # SQLite init, migrations
        │       │   ├── HostRepository.java
        │       │   └── SessionRepository.java
        │       └── ui/
        │           ├── MainController.java
        │           ├── dashboard/
        │           ├── ssh/
        │           │   ├── SshTabController.java
        │           │   └── HostFormController.java
        │           └── docker/
        │               ├── DockerPanelController.java
        │               └── ContainerRowController.java
        └── resources/
            ├── dev/dispatch/
            │   ├── fxml/
            │   │   ├── main.fxml
            │   │   ├── ssh-tab.fxml
            │   │   ├── host-form.fxml
            │   │   └── docker-panel.fxml
            └── css/
                └── dispatch-dark.css
```

---

## Core Modules

### SSH Manager

- **Host profiles** stored in SQLite: name, hostname/IP, port, username, auth method (password/key), key path
- **Sessions as tabs** — each SSH connection opens in a new `TabPane` tab
- **Split panes** — a tab can be split horizontally or vertically (like tmux), each pane is an independent terminal
- **JediTerm** handles terminal rendering: ANSI colors, cursor, resize (PTY SIGWINCH)
- Auth via Apache MINA SSHD — support both password and `~/.ssh` key auth
- All SSH I/O runs on virtual threads (Java 21 `Thread.ofVirtual()`)

### Docker Manager

- After connecting to a host via SSH, `DockerDetector` runs `docker info` over the session
- If Docker is detected, a Docker panel appears as a side panel or sub-tab for that host
- Connection strategy: **SSH tunnel to `/var/run/docker.sock`** — avoids exposing Docker TCP port, no daemon reconfiguration needed on hosts
- `DockerService` wraps docker-java client and exposes:
  - `listContainers()` — all containers (running + stopped)
  - `startContainer(id)` / `stopContainer(id)` / `restartContainer(id)` / `removeContainer(id)`
  - `streamLogs(id)` — RxJava `Observable<String>` with live log output
  - `streamStats(id)` — live CPU%, memory, network I/O
  - `execInContainer(id, cmd)` — opens a terminal session inside the container
  - `listImages()` / `pullImage(name)` — image management
  - `listVolumes()` / `listNetworks()` — basic info

### Storage (SQLite)

Tables:
- `hosts` — id, name, hostname, port, username, auth\_type, key\_path, created\_at
- `sessions` — id, host\_id, connected\_at, disconnected\_at
- `app_settings` — key/value pairs (theme, window size, etc.)

Sensitive data (passwords, passphrases) go into **Java KeyStore**, not SQLite.

---

## UI / UX Guidelines

### Theme — Challenger Deep

The entire application uses the **Challenger Deep** color scheme — deep ocean blues and purples
as the base, with vibrant ANSI-accurate accent colors. AtlantaFX provides the base dark theme
structure; `dispatch-dark.css` overrides it with the Challenger Deep palette.

**Color palette (CSS variables in `dispatch-dark.css`):**

```css
/* --- Challenger Deep palette --- */
-cd-bg:           #1e1c31;   /* main background */
-cd-bg-alt:       #2a2740;   /* sidebar, panels */
-cd-bg-highlight: #3d3b53;   /* hover, selection */
-cd-border:       #565575;   /* borders, dividers */
-cd-fg:           #cbe3e7;   /* primary text */
-cd-fg-dim:       #a6b3cc;   /* secondary text, comments */

/* Accent colors (match terminal ANSI palette) */
-cd-red:          #ff8080;
-cd-green:        #95ffa4;
-cd-yellow:       #ffe9aa;
-cd-blue:         #91ddff;
-cd-magenta:      #c991e1;
-cd-cyan:         #aaffe4;
-cd-white:        #cbe3e7;

/* UI semantic */
-cd-accent:       #91ddff;   /* primary accent — links, focus rings */
-cd-success:      #95ffa4;   /* running containers, connected state */
-cd-warning:      #ffe9aa;   /* warnings */
-cd-error:        #ff8080;   /* errors, stopped/failed */
-cd-purple:       #c991e1;   /* highlights, badges */
```

**Font:** `JetBrains Mono` everywhere — UI labels, terminal, tables, input fields, buttons.
Load from bundled TTF in `resources/fonts/`. Set as default in `App.java` via JavaFX CSS:
```css
* { -fx-font-family: "JetBrains Mono"; }
```

**Terminal (JediTerm) color scheme:** configure `ColorPalette` to match Challenger Deep ANSI
colors exactly so the embedded terminal is visually seamless with the rest of the UI.

### Layout & UX

- Left sidebar (host list) + main content area (tabs) + optional right panel (Docker)
- Terminal tabs: `#1e1c31` background, `#cbe3e7` text, JetBrains Mono — blends with the theme
- Docker panel: container table with colored status badges using `-cd-success` / `-cd-error`
- All long-running operations (connect, pull image, etc.) show progress indicators — never block the UI thread

**JavaFX threading rule:** All SSH/Docker/IO work runs off the FX Application Thread.
Use `Platform.runLater()` or `Task<>` to update UI from background threads.

---

## Workflow & Collaboration Rules

These rules define how Claude should work on this project. They are **mandatory** and override
any default tendency to write large amounts of code at once.

### Granularity — one module at a time

A "module" is a self-contained slice of functionality, for example:
- `storage/` — DatabaseManager + HostRepository (no other layers)
- `ssh/` core — SshService + SshSession (without terminal/JediTerm)
- `ssh/` terminal — JediTerm integration only
- `docker/` — DockerDetector + DockerService (without UI)
- A single UI panel (e.g. host list sidebar)

Claude must **never** implement more than one module per turn. If a task feels too large,
split it and ask which part to start with.

### Mandatory deliverables per module

Every time Claude delivers a module, the response must include all four of these sections —
no exceptions:

**1. 📦 What this does**
Plain-language explanation of what the module does, what problem it solves, and what its
public API looks like (key classes and methods).

**2. 🔗 How it connects to the rest of the project**
Which other modules depend on this one, which modules this one depends on, and where it
fits in the overall architecture diagram.

**3. 🧪 How to test it manually**
Step-by-step instructions to verify the module works before moving on. Can be a simple
`main()` harness, a unit test to run, or manual UI interaction steps — whichever fits best.

**4. ➡️ What comes next**
A short list of the logical next modules to implement, so the developer can decide what
to prioritize. Claude does **not** start implementing them — it waits for approval.

### Stop and wait

After delivering a module and its four sections, Claude must **stop completely** and wait
for explicit approval before writing any more code. Acceptable approval signals:
- "ok", "wygląda dobrze", "zatwierdzone", "go ahead", "następny"
- Or any message that clearly indicates the module has been reviewed

Claude must not proceed autonomously, even if the next step seems "obvious".

### Architectural decisions

When Claude encounters a decision point where two or more approaches are roughly equal,
it must **not** pick one silently. Instead it must:

1. Briefly describe the decision that needs to be made
2. Present each option with: what it is, pros, cons, and a recommendation
3. Wait for the developer to choose before writing any code

Example format:
```
⚖️ Decision needed: how to connect to Docker

Option A — SSH tunnel to /var/run/docker.sock
  ✅ No daemon reconfiguration on hosts
  ✅ Encrypted by default (goes through SSH)
  ❌ Slightly more complex setup in docker-java

Option B — Docker TCP API (port 2375)
  ✅ Simpler docker-java config
  ❌ Requires exposing TCP port on every host
  ❌ Needs TLS setup to be secure

→ Recommendation: Option A
```

### What Claude must never do

- Write code for multiple modules in one response
- Continue to the next module without explicit approval
- Make a significant architectural decision silently
- Refactor unrelated code while implementing a module
- Add dependencies that aren't in the approved tech stack without asking first

---

## Development Guidelines

### Do
- Use **virtual threads** for all blocking I/O (SSH, Docker API calls, DB queries)
- Use **RxJava 3** for streaming data (logs, stats) — subscribe on IO scheduler, observe on FX thread via `JavaFxScheduler`
- Keep UI controllers thin — delegate all logic to service classes
- Use **constructor injection** for services (no static singletons)
- Write unit tests for service layer (`JUnit 5` + `Mockito`) — UI tests with `TestFX` when needed

### Don't
- Never block the FX Application Thread — not even for 100ms
- Don't expose Docker TCP port (2375/2376) on hosts — always tunnel via SSH
- Don't store secrets (passwords, key passphrases) in SQLite or plain files
- Don't mix FXML and programmatic UI in the same view — pick one per component

### Naming conventions
- Classes: `PascalCase`
- Methods/variables: `camelCase`
- FXML files: `kebab-case.fxml`
- CSS classes: `kebab-case`
- Constants: `UPPER_SNAKE_CASE`

### Code structure & debuggability

The codebase must always be easy to navigate and debug. Claude must follow these rules:

**File size — one responsibility per file**
- A single class does one thing. If a class is growing beyond ~150 lines, it's a signal to split.
- Never put multiple top-level concerns in one file (e.g. no `SshAndDockerService.java`).
- Interfaces go in their own files, not nested inside implementations.

**Method size — keep methods short**
- Methods should ideally fit on one screen (~30 lines max).
- If a method needs a comment to explain what a block does, that block should be its own method.
- Prefer many small, clearly named private methods over one long method with inline comments.

**Logging — every significant action must be logged**
Use SLF4J at appropriate levels so problems can be traced without a debugger:
```java
// Levels to use:
log.debug("Connecting to host {} on port {}", host.getHostname(), host.getPort());
log.info("SSH session established: {}", host.getName());
log.warn("Docker not detected on host {}, skipping Docker panel", host.getName());
log.error("Failed to connect to {}: {}", host.getName(), e.getMessage(), e);
```
- `DEBUG` — detailed flow (connecting, sending commands, parsing responses)
- `INFO`  — user-visible state changes (connected, disconnected, container started)
- `WARN`  — non-fatal unexpected situations (Docker missing, config fallback)
- `ERROR` — failures with full exception (`log.error("...", e)` — always pass the exception)

Never use `System.out.println` for anything other than a temporary debug line.

**Exception handling — be explicit, never swallow**
```java
// ❌ Never do this:
try { ... } catch (Exception e) { }

// ✅ Always do this:
try { ... } catch (SshException e) {
    log.error("SSH connection failed for host {}: {}", host.getName(), e.getMessage(), e);
    throw new DispatchConnectionException("Could not connect to " + host.getName(), e);
}
```
Define specific exception types per module (e.g. `DispatchConnectionException`,
`DockerServiceException`) so stack traces point immediately to the right layer.

**Package structure — strictly enforced**
No class may import from a package "above" it in the hierarchy. The allowed dependency
direction is:
```
ui  →  service  →  model
ui  →  storage
service  →  storage
service  →  model
```
`model` and `storage` must never import from `ui` or `ssh`/`docker` services.
This makes it possible to test any service layer without launching the JavaFX UI.

**Comments — explain why, not what**
```java
// ❌ Useless:
// increment counter
counter++;

// ✅ Useful:
// Docker socket path differs on rootless Docker installations
String socketPath = isRootless ? "/run/user/1000/docker.sock" : "/var/run/docker.sock";
```
Every public class and public method must have a one-line Javadoc describing its purpose.

---

## Environment & System Requirements

### Supported platforms
Dispatch must run correctly on all three platforms — no platform-specific shortcuts allowed:
- **Linux** (primary development environment)
- **macOS** (Apple Silicon + Intel)
- **Windows 10/11**

### Required
- **Java 21 LTS** — must be installed by the user (document this in README)
- **JavaFX 21** — bundled via Gradle, not required system-wide
- **JetBrains Mono** — bundled inside the JAR under `resources/fonts/JetBrainsMono/`
  Load at startup in `App.java`:
  ```java
  Font.loadFont(App.class.getResourceAsStream("/fonts/JetBrainsMono/JetBrainsMono-Regular.ttf"), 13);
  Font.loadFont(App.class.getResourceAsStream("/fonts/JetBrainsMono/JetBrainsMono-Bold.ttf"), 13);
  ```

### Cross-platform rules
- Use `Path` and `Paths.get()` everywhere — never hardcode `/` or `\` as path separators
- SSH key default path: resolve via `System.getProperty("user.home")` + `/.ssh/id_rsa`
- SQLite database location: `~/.dispatch/dispatch.db` (user home, works on all platforms)
- KeyStore location: `~/.dispatch/dispatch.jks`
- Never assume a shell — Docker detection runs `docker info` via SSH exec, not a local shell

---

## Git Workflow

### Branching
- `main` — stable, working code only. Every commit on main must compile and run.
- `feat/<module-name>` — one branch per module (e.g. `feat/ssh-core`, `feat/docker-service`)
- Merge to `main` only after manual testing and approval.

### Commit convention — Conventional Commits
Format: `type(scope): short description`

| Type | When to use |
|---|---|
| `feat` | new working module or feature |
| `fix` | bug fix |
| `refactor` | restructure without changing behaviour |
| `style` | formatting, CSS changes |
| `test` | adding or fixing tests |
| `docs` | README, CLAUDE.md, Javadoc |
| `chore` | build config, dependencies |

Examples:
```
feat(storage): add DatabaseManager and HostRepository
feat(ssh): implement SshService with connect/disconnect
fix(docker): handle rootless Docker socket path
docs(claude): add git workflow section
```

### Claude's responsibility
After a module is approved, Claude must suggest the exact commit message to use, e.g.:
> 💾 Suggested commit: `feat(storage): add DatabaseManager and HostRepository`

Claude does not run `git` commands — it only suggests the message.

### Branch & merge rules
- Claude **always** works on a `feat/<module-name>` branch — never directly on `main`
- At the start of each module Claude must state which branch it's working on, e.g.:
  > 🌿 Working on branch: `feat/ssh-core`
- Claude **never** merges to `main` on its own — it only suggests the merge command after approval:
  > ✅ Ready to merge. When you're happy, run:
  > ```
  > git checkout main
  > git merge --no-ff feat/ssh-core
  > git push origin main
  > ```
- The `--no-ff` flag is mandatory — preserves branch history, no fast-forward merges
- After suggesting the merge, Claude waits for confirmation before starting the next branch

---

## Versioning & Changelog

### Version scheme — SemVer (`MAJOR.MINOR.PATCH`)
- `MAJOR` — breaking change or complete module overhaul
- `MINOR` — new working module added (SSH, Docker, etc.)
- `PATCH` — bug fix or small improvement within an existing module

Starting version: **0.1.0** (pre-release, no stable API yet).
Version is defined once in `build.gradle.kts`:
```kotlin
version = "0.1.0"
```

### CHANGELOG.md
Maintained manually at the project root. Format (Keep a Changelog):
```markdown
## [0.2.0] - 2025-xx-xx
### Added
- SSH core module: connect, disconnect, session management
- Host profiles stored in SQLite

## [0.1.0] - 2025-xx-xx
### Added
- Project scaffold, Gradle setup, DatabaseManager, HostRepository
```

Claude must suggest a CHANGELOG entry alongside the commit message when a module is approved.

---

## Code Formatting

### Formatter — Google Java Format
Use **google-java-format** via Gradle plugin. All code Claude writes must comply with this style.
Add to `build.gradle.kts`:
```kotlin
plugins {
    id("com.diffplug.spotless") version "6.25.0"
}

spotless {
    java {
        googleJavaFormat("1.21.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
```
Run before every commit: `./gradlew spotlessApply`

### Key style rules (enforced by google-java-format)
- Indent: **2 spaces** (not 4, not tabs)
- Max line length: **100 characters**
- Imports: no wildcards (`import java.util.*` is forbidden), sorted automatically
- Braces: always on the same line (`} else {`, never on a new line)

### Claude must
- Write all code already compliant with google-java-format style
- Never submit code with unused imports
- Keep lines under 100 characters — break method chains across lines if needed

---

## Connection State & Reconnect Strategy

### Session states
Every SSH session has an explicit state tracked in `SshSession`:
```
DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED
                                ↓
                           LOST (unexpected drop)
```

### On unexpected disconnect (LOST state)
- Tab header changes to show a `⚠ disconnected` indicator (use `-cd-warning` color)
- Terminal becomes read-only — input is blocked
- A **"Reconnect" button** appears as an overlay on the terminal pane
- Docker panel for that host collapses and shows "Host offline"
- No auto-reconnect — the user initiates it manually via the button

### On reconnect attempt
- State transitions back to `CONNECTING`
- If successful: terminal resumes, Docker panel re-checks for Docker, button disappears
- If failed: show error message in the tab with the failure reason (timeout, auth failure, etc.)

### Timeouts
- Connection timeout: **10 seconds** (configurable in `app_settings`)
- Keep-alive interval: **30 seconds** via SSH keep-alive packets (prevents silent drops)
- Keep-alive max failures before marking as LOST: **3**

---

## Key Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // JavaFX
    implementation("org.openjfx:javafx-controls:21")
    implementation("org.openjfx:javafx-fxml:21")

    // UI Theme
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")

    // SSH
    implementation("org.apache.sshd:sshd-core:2.12.1")
    implementation("org.apache.sshd:sshd-sftp:2.12.1")

    // Terminal
    implementation("com.jediterm:jediterm-pty:3.x")

    // Docker
    implementation("com.github.docker-java:docker-java-core:3.3.4")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.4")

    // Storage
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Reactive
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.3")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.testfx:testfx-junit5:4.0.18")
}
```

---

## Out of Scope (for now)

- Kubernetes / kubectl integration
- Proxmox REST API panel
- Multi-user / shared config
- Mobile or web version
- Plugin system

---

## Future Ideas

- SFTP file browser per host (drag & drop upload/download)
- Live metrics dashboard (CPU/RAM/disk graphs) via SSH + `vmstat`/`df`
- Proxmox VM management panel (start/stop/snapshot via API)
- Ansible playbook runner with live output
- Port forwarding manager UI
