# 0.4.0の検証

## 自動・仮想端末

- JVMテスト83件成功。追加15件では、エンジン破棄の正常完了・所有方針・JNI切断状態・同じロックの所有を確認。キャッシュエンジン、破棄失敗、動作中・状態不明、別ロックの保持を確認した。
- 停止バッチのテストでは、最初の停止失敗後も残りを実行すること、全件失敗しても元の件数で終わること、正常な戻り値の維持、別スキャナー・別コールバック・null引数の例外を透過すること、同値でも別実体なら所有者扱いしないこと、LinkageErrorを握りつぶさないことを確認した。
- Android 16/17のエミュレーターで、従来7対象と追加2対象、計9本の実APKへのDexKit照合が成功。LANDrop/Continuityの全メソッド・フィールドを同じAPKのクラスローダーで解決できた。
- LANDropの実APKのFlutterJNI.isAttachedを、ネイティブエンジンへ接続しない孤立したJavaラッパーで実行した。空のハンドルならfalse、仮の非nullハンドルならtrueとなり、同じEngineShutdownPolicyがそれぞれ許可・拒否することを確認。エンジンの起動やネイティブ終了そのものを検証した意味ではない。
- ContinuityのshutdownNow参照は親インターフェースExecutorServiceに解決されていた。ScheduledExecutorServiceとの継承を考慮し、同じ引数と戻り型の両参照を照合する。難読化名を固定して代用していない。
- Android 16/17で12対象の設定画面を表示し、最後のContinuity Serviceまでスクロールできた。Android 17はダークモード・文字150%も確認。文字150%では8回のスワイプで末尾へ届かず、画面の実際の内容を確認して追加スクロールした。
- Continuityの個別スイッチOFFがProviderへ反映され、設定アプリのプロセス再起動後も保存され、ONへ戻せることをエミュレーターで確認。試験後はONへ復元。
- releaseのビルド・lint・整形検査が成功。常駐サービス、アラーム、定期ジョブ、ポーリングは追加していない。従来のGovee起動後の初期化方式は維持している。

## 実機・効果の範囲

調査時の実機操作は統計読み取りとAPK取得に限定した。その後、ユーザーの操作枠の許可を受けて署名済みrelease 0.4.0（code 10）をインストールし、端末内APKのSHA-256一致を確認した。LANDropとContinuity ServiceをVectorスコープへ追加し、この2対象のプロセスだけを再読み込みした。Continuity Serviceのマニフェストに定義されたcom.samsung.android.ceも対象に含む。Bluetoothプロセスや無線全体は再起動していない。

新規2対象ともdiscovery=ready・hostState=active・エラーなしを確認した。MIME種別取得による通常のApplication初期化だけで確認し、Activityや機器制御は操作していない。どちらも初回探索は1回で、キャッシュはモジュール自身の/data/user/0/dev.ryuya.appbatterytools/files/dexkit内に保存されていた。処理後のスレッド一覧で解析ワーカーが終了していることを確認した。

boot ID、system_server、SystemUI、ホームのPID、SELinux、前面アプリはすべて維持された。OS・SystemUI・ホームの再起動、音声再生、認証・他アプリ設定の変更はしていない。

追加2件の後始末が実機で現在漏れているとの観測や、長期の削減量は未確認。LANDropの実転送・バックグラウンド転送中の終了競合、Continuity接続中の機器との組み合わせについても、APK照合・純粋な所有関係テストと分ける。元の不要な状態が発生していなければ、この補正だけでは削減効果は出ない。

調査した追加10本と候補の採否は[第4回](investigation/round4-2026-11-08.md)に記録した。SmartNewsの広告SDKは継続調査候補であり、この版では変更していない。
