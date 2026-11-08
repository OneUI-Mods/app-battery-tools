# App Battery Tools

- Keep Android implementation in Kotlin and support Android 16/17 with API 102.
- This app must not add a background/foreground service, periodic worker, alarm or polling loop. Discovery and Binder-metadata workers release idle threads after one second.
- Hooks own only the selected application's registrations. Do not change global location providers, permissions, geofences, notification delivery, calls or audio.
- APKs and decompiled third-party code belong in the root `.workspace`, never in Git or source ZIPs.
- Discovery uses semantic anchors, matching types and uniqueness; cache results including misses in this module's own internal data. Normal hook callbacks perform no DEX scans.
- Release APKs only, continuous signing identity, public source, matching source archive and hashes. Follow `docs/releasing.md`; never publish signing keys or passwords.
