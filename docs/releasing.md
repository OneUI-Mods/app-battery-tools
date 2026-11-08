# リリース

JDK 21以降とAndroid SDK 37を用意し、`./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease :app:spotlessCheck`を実行します。

配布用PKCS12鍵は非公開の`.signing/release.keystore`、パスワードは`.signing/password`、aliasは`appbatterytools-release`です。同じ鍵を継続し、新しく作り直しません。アプリ別バックアップはDocumentsとWindows Bドライブの指定された非公開署名鍵フォルダに保存します。

変更をコミットした後に`python3 tools/package_release.py --source-ref HEAD`を実行します。署名済みrelease APK、同一コミットのソースZIP、署名検証結果、release-metadata.json、SHA256SUMSをGitHubの安定版Releaseへ添付します。タグは移動しません。debug APK・署名なしAPK・第三者APK・秘密情報は配布しません。

パッケージ・SDK・署名・16KB整列・API 102のBinder provider・ライセンス一覧・検証用Activityの不在を検証します。このアプリ自身のサービスやForeground Service権限が追加された場合も配布を止めます。CI artifactへAPKをアップロードしません。
