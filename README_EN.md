# notifyme · Local-first WeChat Notification Intelligence

[![build](https://github.com/solidjoker/notifyme/actions/workflows/android.yml/badge.svg)](https://github.com/solidjoker/notifyme/actions/workflows/android.yml)
[![release](https://img.shields.io/github/v/release/solidjoker/notifyme?label=release)](https://github.com/solidjoker/notifyme/releases/latest)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **TodayToTomorrow for little mermaid**

English | [简体中文](README.md)

A local-first tool for capturing WeChat notifications, managing conversations, and running AI analysis on them.
Notification content never leaves your device unless you say so — analysis runs through an OpenAI-compatible
endpoint you configure yourself (cloud or on-device model), and messages that need action automatically become
calendar events or alarms.

- 🤖 **Android**: shipping APK, fully standalone, works completely offline
- 🍎 **iPhone**: PWA viewer (requires the optional server); a native iOS viewer + reminder push is planned
- 🖥️ **Server (optional)**: cross-device viewing, history backfill, remote access

---

## Features

**Capture & conversation management**

- Dual capture channels: notification listener + accessibility service; supports WeChat / Feishu / DingTalk and
  more (enable sources in the console)
- Home screen groups messages by conversation → date, collapsible; same-named conversations from different apps
  are shown separately
- Watchlist (⭐): an empty list watches everything, or specify conversations precisely; the PWA console filters by app
- Multiple deletion gestures: swipe conversation/date headers, long-press multi-select batch delete

**AI analysis**

- Two-stage analysis: stage 1 decides whether action is needed, stage 2 breaks down the tasks
- ⚡ badge marks "needs action" results, clearable with one tap after reading
- Per-conversation custom prompts
- On-device models with on-device agent inference
- Any OpenAI-compatible endpoint: cloud models or a local agent both work

**Reminders**

- Three-level fallback chain: system calendar event → calendar insert Intent → local alarm
- 📅 badge marks conversations with reminders; tap to open reminder management

**Sync & backfill (server required)**

- Push messages to your self-hosted Flask server (LAN or tunneled public access)
- WorkManager periodic jobs survive sleep and reboots
- Optional Android / PC WeChat database history backfill scripts

---

## Architecture Overview

```
WeChat notifications ──► NotificationListenerService ─┐
WeChat UI states ─────► Accessibility Service ────────┤
                                                      ▼
                                        MessageStore (files/messages.jsonl)
                                                      │
                          ┌───────────────────────────┼───────────────────────┐
                          ▼                           ▼                       ▼
                 Home grouping/search         AI analysis worker          Sync worker
                  (conversation→date)      (cloud API / on-device)    (self-hosted server)
                                                      │
                                                      ▼
                                    AnalysisStore + ⚡ needs-action flag
                                                      │
                                                      ▼
                        CalendarHelper: calendar event → calendar Intent → AlarmHelper alarm
```

Fuller architecture docs live in [docs/architecture/README.md](docs/architecture/README.md) (Chinese),
with 9 C4-style diagrams (system context, Android layering, capture channels, analysis pipeline,
reminder chain, feature panorama, server, build & deploy, sync sequence).

---

## Getting Started

### Android (current APK)

1. Download the latest `notifyme-open.apk` from
   [Releases](https://github.com/solidjoker/notifyme/releases/latest) (a `.sha256` checksum sits next to it),
   or build it yourself per [docs/BUILD.md](docs/BUILD.md) (Chinese)
2. After installing, grant the following when prompted:
   - **Notification access** — required
   - (Optional) **Accessibility service**: enables direct WeChat UI reading
   - (Optional) **Calendar permission**: for writing to-do events
3. In the app's analysis settings, enter your own OpenAI-compatible endpoint, key, and model name

The release build (open flavor) ships with zero preconfigured servers or keys — completely offline until you configure it.

### iPhone (PWA viewer)

iPhone currently views synced messages and analysis results through a PWA backed by the optional server:

1. Deploy the server on a PC or host (see "Server deployment" below)
2. Open `http://<your-server>/console` in Safari
3. "Share → Add to Home Screen" and it behaves like a native app

> iOS limitation: no third-party app can read other apps' notifications in the background the way Android
> notification access does, so the iPhone can only view synced data, not capture locally.
> The planned native iOS client is a "viewer + reminder push" (pushing needs-action results to the iPhone),
> not a local capturer. See [docs/ROADMAP.md](docs/ROADMAP.md) M5 (Chinese).

### Server deployment (optional)

```bash
cd server
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt

# Auth token (optional but strongly recommended): create secrets.local.bat manually with
#   set WEIXIN_TOKEN=your-long-random-token
python app.py
```

On the LAN, point the app's server address at `http://<PC-IP>:8000/weixin`.
For public access, put your own HTTPS reverse proxy in front (all domains in the repo docs are placeholders).
See [server/README.md](server/README.md) (Chinese).

---

## Build from Source

Requires JDK 17 + Android SDK (compileSdk 34, minSdk 26).
Full build instructions (environment, flavors, key injection, adb install) are in [docs/BUILD.md](docs/BUILD.md) (Chinese).

Quick release-flavor build:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleOpenDebug
# output: app/build/outputs/apk/open/debug/notifyme-open.apk
```

---

## Privacy

- Messages stay in the app's private storage by default; fully offline until configured
- Three privacy modes (off / redact-before-send / on-device only); 8 categories such as phone numbers are
  redacted on-device, with a preview of exactly what will be sent
- Data only reaches an endpoint you configure and trigger yourself
- Delete by conversation / date / batch; uninstalling wipes everything

Full details in [PRIVACY.md](PRIVACY.md).

## Legal

Use only on your own devices with data you are authorized to process. Make sure you have the necessary
consent before capturing or analyzing anyone's messages, and comply with local law and WeChat's terms.

---

## Roadmap

Milestone-based; the full schedule, sizing, and risks are in [docs/ROADMAP.md](docs/ROADMAP.md) (Chinese).

1. **Cross-app notification management** ✅ done (M2: WeChat / Feishu / DingTalk…; floating notifications M9 done on emulator)
2. **Privacy protection** ✅ done (M3: three modes + preview)
3. **Local agent & model improvements**: on-device capability, prompts, analysis quality (M4 spike done)
4. **Native iOS viewer + reminder push**: see results on iPhone, get reminded (no local capture)
5. **UI polish**: interaction, readability, multi-device adaptation
6. **Beyond notifications**: fold in data such as your own outgoing messages
7. **Smart hardware**: notification interplay with wearables / home devices
8. **Other**: discuss in Issues

## Contributing

Issues and PRs welcome. By submitting you agree your contribution is released under the project's MIT license.

## License

[MIT License](LICENSE) © 2026 solidjoker. Third-party component licenses in [THIRD-PARTY.md](THIRD-PARTY.md).
