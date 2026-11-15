# Battery-use investigations of third-party apps

[日本語](README.md) · English

Updated: 2026-11-08. Added the [system-wide memory investigation and three corrections (Japanese)](system-memory-2026-11-08.md).

Additional checks of six system components, the display-adjustment waiting-thread correction, and the screenshot investigation are recorded in [round 5 (Japanese)](system-round5-2026-11-08.md).

Ten further apps are covered in [round 4 (Japanese)](round4-2026-11-08.md). Version 0.4.0 added cleanup after LANDrop's owning engine stops and continuation of Continuity Service stop requests. See its [validation scope (Japanese)](../testing-0.4.0.md).

The latest investigation of nine additional apps is in [round 3 (Japanese)](round3-2026-11-08.md). Stopping Weathernews periodic work and returning SmartThings locks after interrupted discovery became new conditional candidates. Digi Police implementation and physical-device comparison were completed; Link to Windows, Govee, Sesame, EQ, and ECOVACS were added in 0.2.0. Their implementation is described in the [design (Japanese)](../additional-hooks.md), coverage and unverified cases in the [validation record (Japanese)](../testing-0.2.0.md), and earlier observations in [round 2 (Japanese)](round2-2026-11-08.md).

Additional APK analysis of system apps is collected in the [three Samsung-component investigations (Japanese)](system-apps-2026-11-08.md). The four round-3 candidates were implemented in 0.3.0, with a separate [validation scope (Japanese)](../testing-0.3.0.md).

## Approach

- Combine supported improvements in App Battery Tools, with switches for individual apps and individual corrections.
- Do not add changes with only marginal benefits. Prioritize substantial waste whose cause and correction can be explained from both consumption records and APK analysis.
- Distinguish bug fixes from changes that save power by degrading required notifications, synchronization, calls, location accuracy, or responsiveness.
- Preserve Digi Police behavior while **Receive area notifications** is on. Suppress unnecessary positioning-service starts only for verified off-state settings. Do not assume the problem is simply a permanent cleanup leak after shutdown.
- Use Kotlin, libxposed API 102, Android 16/17 support, DexKit validation of semantics/types/uniqueness, and per-app switches.
- Store DexKit results in the module's own internal data. Rescan only on first use or target APK updates; do not perform full DEX discovery inside normal hooks or drawing callbacks.

## Investigated scope

The first pass collected registrations for 581 user-installed apps, including 18 self-developed apps in the management catalog. Battery statistics, alarms, jobs, active location requests, and CPU information were used to select candidates. Only Digi Police and Link to Windows received detailed APK-path analysis in that pass.

The second pass refreshed registrations for the then-current 591 apps and additionally examined Bluetooth, multicast, per-thread CPU, sockets, and database resources. Five new APKs were acquired. Normal Govee analysis recovered only a wrapper; its main implementation had not been analyzed at that point. This does not mean all 591 APKs were decompiled.

The battery record covered roughly 14 hours from 02:59 on 2026-11-08, including about 7 hours 29 minutes with the screen off. Alarm statistics included a different, longer period; their counts must not be interpreted as frequencies within those same 14 hours. Short CPU samples include user interaction and ADB-investigation overhead, not steady idle consumption.

## Candidates from rounds 1 and 2

These are pre-implementation screening observations. For support in 0.2.0, see the records linked above.

| Target | Investigation stage | Observations | Assessment |
| --- | --- | --- | --- |
| [Digi Police](digipolice.md) | APK and device statistics | High-accuracy location requests every 20 seconds. A new receiver is registered on every service start, with no corresponding unregister call found in the stop path | Highest priority. A mismatch between saved OFF settings and service startup was confirmed. Manually stopping the original service also stopped GPS, so a permanent cleanup leak was not established |
| [Link to Windows](link-to-windows.md) | Java, native code, and device statistics | The same process grew from 317 to 345 threads and 105 to 122 sockets. A light repeat sample still used about 4.1% of one CPU core. Duplicate database creation paths were also identified | Next highest priority. Establish ownership and the normal cleanup path for completed connections |
| [ECOVACS HOME](round2-2026-11-08.md) | APK and device statistics | A Matter-specific MulticastLock was acquired, but no matching release was found in the manager's shutdown path | Conditional candidate. No process or lock was active at that time; verify when it becomes unnecessary |
| [Govee Home / Sesame](round2-2026-11-08.md) | BLE statistics and APK | Long-running BLE discovery. Sesame used fixed LOW_LATENCY settings and service/Activity stop relationships were checked. Govee's main code had not been analyzed | Conditional candidate. Distinguish this from discovery required by automatic connection, unlocking, and widgets |
| [Google Play services](screened-apps.md) | Device statistics | Many location alarms, including work performed on behalf of Digi Police | Do not suppress it globally. Fix the requesting app first |
| [Earpiece EQ](screened-apps.md) | Device statistics | Estimated screen-off CPU consumption of about 3.62 mAh | Insufficient evidence of a large improvement at that stage. Avoid degrading audio processing |
| [J:COM / modified DiXiM](screened-apps.md) | Device statistics | Estimated screen-off Wi-Fi consumption of about 3.45 mAh | Deferred due to small size. Transfer and discovery requirements had not been examined |
| [Home Assistant](screened-apps.md) | Device statistics | Estimated screen-off total of about 2.25 mAh; sensor and location requests | Deferred because the potential benefit was small relative to automation impact |
| [KWGT / Kustom Widget](screened-apps.md) | Device statistics | Estimated screen-off CPU consumption of about 0.514 mAh | No evidence of heavy resident processing with the screen off |
| [MacroDroid](screened-apps.md) | Device statistics | Estimated screen-off CPU consumption of about 1.36 mAh; PASSIVE location requests | Do not save power by removing automation functionality |
| [Nature Remo](screened-apps.md) | Device statistics | Estimated screen-off CPU consumption of about 0.00268 mAh | Not a substantial candidate in this record |
| [MultiPicture Live Wallpaper](screened-apps.md) | Device statistics | Estimated screen-off CPU consumption of about 0.0221 mAh | Not a substantial candidate in this record |
| [Keikyu app](screened-apps.md) | Device statistics | Marketing SDK wakeups; about 0.11 seconds of CPU time during this battery period | Wakeup counts alone do not establish major consumption; deferred |

Estimated mAh values are Android model allocations, not isolated physical battery measurements or measured savings from a hook. The overall GPS estimate must not be attributed exclusively to Digi Police.

## Evidence storage

Raw statistics, APKs, decompiled output, app settings, and location data are excluded from public source. They are kept locally under `.workspace/third-party-battery-20261108/`. This directory contains only summaries of relevant facts and proposed improvements. Third-party APKs are not publicly redistributed.

The linked investigation records are currently in Japanese.
