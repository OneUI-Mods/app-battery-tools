# App Battery Tools

[日本語](README.md) · English

A Vector / Xposed module that reduces unnecessary battery use in selected apps. Supports Android 16/17 and libxposed API 102.

## Automatic startup after boot

Automatic startup after device boot can be switched on or off in settings. The default is on when no choice has been saved; an existing off choice is preserved. Startup only restores configured behavior and does not override feature switches, permissions, or lock-state restrictions. The switch is in the About tab. Turning it off keeps on-demand setup available when a supported app opens. Settings preparation waits for the first unlock and adds no resident service or polling.

## First unlock after boot

Hook initialization that needs settings or cached discovery results waits for the first user unlock after boot. A later ordinary screen lock does not repeat initialization. Existing screen-off power-saving and security behavior is preserved. Unlock delivery is event-driven, with no polling or new resident service.

## Supported features

| App or component | Independently switchable behavior |
| --- | --- |
| Samsung display adjustment | Let a dedicated waiting thread become idle after camera use, without relying on garbage collection |
| Link to Windows | Share an identical beacon database and release communication clients that arrive after a failed handoff |
| Govee Home | Stop BLE discovery while disconnected from Wi-Fi and resume it on reconnection |
| Sesame | Stop BLE discovery while disconnected from Wi-Fi and resume it on reconnection |
| Earpiece EQ | Reuse calculations for identical output-device IDs and validation regular expressions |
| ECOVACS HOME | Release a dedicated multicast lock after its Matter controller stops, and reacquire it when reused |
| Weathernews | Stop obsolete periodic timers when WxBeacon2 is disabled or its interval changes |
| SmartThings | Return a dedicated lock when mDNS discovery startup is interrupted before cleanup takes ownership |
| Tapo | Return the lock reference acquired by a Matter discovery call when startup fails synchronously |
| Simeji | Disable the diagnostic SDK's ANR monitor registration that runs every 500 ms |
| Multiple Picture Wallpaper | Release the wallpaper process's GPU caches after it stays hidden for 4 seconds |
| LANDrop | Release its discovery lock after confirming that the owning Flutter engine has stopped |
| Continuity Service | Continue the remaining BLE stop requests even if some stop requests throw exceptions |
| Digi Police | Suppress unnecessary positioning-service starts while area notifications are off, and release the service's receivers on shutdown |

To avoid conflicting with Govee's startup protection, preparation begins only after the app finishes startup. Discovery already started before that point is affected from its next registration.

Enabling the Wi-Fi restriction for Govee or Sesame may prevent BLE device discovery, automatic unlocking, and similar functions while using mobile data alone. Existing Bluetooth connections are not disconnected. Discovery canceled by the app is not restarted when Wi-Fi returns.

Active Link to Windows connections, notification synchronization, and file-transfer intervals are unchanged. EQ audio processing and liveness-check intervals are also unchanged. An ECOVACS lock is released only after its controller is confirmed to have stopped; closing a screen does not unconditionally terminate Matter connections.

The Weathernews correction takes effect on sensor-setting changes after hooks load. It does not recover timers whose references have already been lost. Simeji's affected diagnostic SDK can no longer create new ANR reports; typing, conversion, themes, and Android's own crash handling are unchanged.

See the [0.5.0 implementation and validation record (Japanese)](docs/testing-0.5.0.md) for the current scope. There is no guaranteed battery-saving percentage.

LANDrop releases resources only after confirming shutdown of the engine owned by that screen, never during background transfer or discovery. The Continuity Service correction lets the original stop request run to completion.

## Digi Police settings

The module prevents its positioning service from running when **Receive area notifications** is off. When the area-notification service ends, the location receivers registered by that service are also released. With the setting on, request intervals, accuracy, maps, and call blocking retain their original behavior.

## Setup

1. Install the release APK. Enable the module in Vector and enable the scopes of the apps you want to use it with.
2. Open **Status** in App Battery Tools and confirm that discovery has completed.
3. Restart the target apps. An OS restart is not required.

The **Apps** tab contains a master switch and app-specific switches. Windows database sharing and client cleanup have separate switches. Changes take effect after restarting the affected app.

## Background operation

The module app has no resident service, periodic timer, alarm, or scheduled job. Hooks run inside the target apps. Initial discovery and Binder-metadata workers shut down their threads one second after becoming idle. Android may retain the process in its cache, but it performs no periodic monitoring.

DexKit results are stored only in this module's internal app data. Discovery runs on first use, after a target update, or after a discovery-rule change. Normal startup and hook callbacks do not scan all DEX files.

## Compatibility and limitations

The Digi Police paths were checked against version 3.7.0. DexKit validates the types, meaning, and uniqueness of Java service and location registration/cleanup matches. The settings are implemented in Dart, so the off-state check is enabled only for verified AOT versions. If the settings model changes, start suppression is skipped and the status screen explains this. Stale settings are not used to suppress required notifications.

The benefit depends on how long unnecessary positioning would otherwise have run. A uniform reduction percentage is not guaranteed.

Source: [OneUI-Mods/app-battery-tools](https://github.com/OneUI-Mods/app-battery-tools). Library-specific licenses are available under **About** in the app.

Developer information: [Development (Japanese)](docs/development.md), [Releasing (Japanese)](docs/releasing.md), and the [investigation index](docs/investigation/README.en.md).

[Build environment / ビルド環境](docs/build-environment.md)
