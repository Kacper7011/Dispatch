# Dispatch 🚀

> A personal homelab management desktop application — built with JavaFX and a lot of vibe coding.

---

## What is Dispatch?

Dispatch is a desktop application I'm building to serve as a central control panel for my self-hosted homelab. Instead of jumping between multiple terminal windows, SSH sessions, and browser tabs to manage my infrastructure, Dispatch brings everything into one place.

Think of it as a combination of:
- **VS Code Remote-SSH** — manage SSH sessions with a proper terminal emulator, split panes, and saved host profiles
- **tmux** — multiple terminal sessions per host, all inside one window
- **Portainer** — Docker container management (start/stop/restart/logs/exec) with auto-detection per host

The goal is not to build the next big DevOps tool. It's a personal project built specifically around my homelab environment — a 3-node Proxmox cluster with a handful of VMs and Docker workloads running on each.

---

## Why?

Two reasons:

**1. I needed this tool.**
Managing a homelab means constantly switching contexts — SSH here, `docker ps` there, checking logs somewhere else. I wanted a single pane of glass that actually fits how I work, not a generic solution that almost fits.

**2. Vibe coding experiment.**
This project is also a deliberate experiment in AI-assisted development — building a non-trivial desktop application by collaborating closely with Claude, working module by module with human review at each step. I wanted to see how far you can get, how fast, and what the friction points are when building something real this way.

---

## Status

> 🚧 **Early development — nothing works yet.**

This project is in the planning and scaffolding phase. Features will be added incrementally, one module at a time.

| Module | Status |
|---|---|
| Project scaffold & Gradle setup | ⏳ Planned |
| Storage (SQLite host profiles) | ⏳ Planned |
| SSH core (connect, sessions) | ⏳ Planned |
| Terminal emulator (JediTerm) | ⏳ Planned |
| Docker auto-detection | ⏳ Planned |
| Docker management panel | ⏳ Planned |
| Main UI layout | ⏳ Planned |

---

## Tech Stack

| | |
|---|---|
| Language | Java 21 LTS |
| UI | JavaFX 21 |
| SSH | Apache MINA SSHD |
| Terminal | JediTerm |
| Docker API | docker-java |
| Database | SQLite |
| Theme | AtlantaFX + Challenger Deep palette |
| Font | JetBrains Mono (bundled) |
| Build | Gradle 8 (Kotlin DSL) |

---

## Requirements

- Java 21 LTS or newer
- Works on Linux, macOS, and Windows

---

## Running Locally

> Instructions will be added once the project has something runnable.

---

## Project Structure

```
dispatch/
├── src/
│   └── main/
│       ├── java/dev/dispatch/   # Application source
│       └── resources/           # FXML, CSS, fonts
├── CLAUDE.md                    # AI collaboration guidelines
├── CHANGELOG.md                 # Version history
└── build.gradle.kts
```

---

## License

Personal project — no license yet.
