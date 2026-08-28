# Entity Inventory System

A private Android prototype that treats Markdown entity records as the source
of truth and uses QR codes and NFC tags for inventory, travel checklists, and
location reconciliation.

## Repository scope

This repository contains only the reusable application and data-model work:

- the native Android prototype in `安卓原型/`;
- generic entity, audit, QR, and NFC design notes;
- Gradle Wrapper, build scripts, and JVM tests.

Real inventory records, residence/container notes, contact settings, generated
QR codes, photos, APKs, and local toolchains stay outside Git.

## Core invariants

- Markdown files remain the authoritative records;
- `entityId` and `tagId` are separate stable identifiers;
- QR and NFC observations share the same inventory pipeline;
- an item not scanned in one audit is only unconfirmed, never automatically lost;
- audit history is append-only.

## Build and test

Requirements:

- JDK 17;
- Android SDK Platform 35;
- Android Build Tools 35.

From `安卓原型/` on Windows:

```powershell
.\scripts\build-windows.ps1 -Target test
.\scripts\build-windows.ps1 -Target assembleDebug
```

The build script temporarily maps the project to an ASCII-only drive letter so
Gradle tests work reliably when the checkout path contains Chinese characters.

## Privacy and licensing

This is a private development repository. It deliberately excludes the user's
actual Obsidian `50_实体/` directory and all personally identifying tag payloads.
No open-source license has been granted at this stage.

