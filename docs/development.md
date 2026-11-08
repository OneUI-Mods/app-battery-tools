# 開発

Android側はKotlin、compile/target SDK 37、min SDK 36、libxposed API 102です。`app/src/main`に実機試験用Activityや起動代行サービスを追加しません。

Windowsにリンク、BLEのWi-Fi制限、EQ、ECOVACSの追加設計は[追加対象の設計](additional-hooks.md)を参照してください。対象別の状態と内部キャッシュを持ち、設定やキャッシュを他アプリのデータ領域へ受け渡しません。

## 処理の所有者

ModuleProviderが固定の対象と署名を検証し、DexKitをワーカーで実行します。キャッシュはモジュール自身の`files/dexkit/<package>/`に保存します。対象の版・更新時刻・APK/splitの配置と属性・署名・解析ルールで世代を分け、失敗・中断・破損結果も記録して同じ版の無限再探索を防ぎます。フック先の保存領域や共有ストレージへキャッシュを渡しません。

DigiPoliceDiscoveryはサービスの通知文言、Service継承、onStartCommandの型、位置登録と同じコールバック型を使う解除メソッドを一意に照合します。短い難読化名に依存して新しい版を推測しません。Dartの設定モデルはDexKitの解析対象ではないため、確認したAOTハッシュの版でだけ`titanium/IS_ARROWED_AREA_NOTIFICATION`を採用します。違う版は開始抑止を見送り、Javaの終了時解除だけに限定します。

## フック

Application.attach後、既存のApplication実体、InstrumentationのonCreate経路を一度だけ初期化するガードでまとめ、検証済み設定を一度取得し、対象アプリ内へフックを設置します。初回の解析が未完了ならそのプロセスへの適用を見送り、解析完了後の対象再起動を案内します。設置に失敗した場合は部分的なフックも解除します。

設定が確認済みのfalseなら、Digi Police内の明示的なstartService/startForegroundService要求をサービス生成前に抑止します。OS・sticky・外部からの要求はこのフックを通らないため、純正onStartCommandのstartForegroundまで完了させ、その後に位置コールバックの解除とstopForeground/stopSelfを行い、START_NOT_STICKYを返します。FGSの起動義務を満たす前に止めません。true・キーなし・型違い・未検証版は純正へ進みます。設定変更はSharedPreferencesの通知だけで検出し、OFFになったとき該当サービスだけを止めます。ONへの変更でモジュールが勝手に測位を開始することはありません。

位置コールバックの所有者はサービス実体で管理します。onStartCommand中に発生した登録だけを追跡し、onDestroyで対応するクライアント・コールバックの解除を要求します。停止→再開始でも新しい実体の登録は触りません。非同期Taskの完了を確認したという表示は行わず、診断は「解除要求」とします。

## 自身を常駐させない

サービス・Foreground Service権限・アラーム・定期ジョブ・ポーリングを持ちません。初回解析とBinder接続情報取得のワーカーはアイドル1秒でスレッドを終了します。設定画面は可視期間だけContentObserverを登録し、状態変化または利用者の更新操作で描画します。対象アプリ内の設定リスナーもイベント駆動です。

`tools/package_release.py`はサービスとFGS権限の混入を拒否します。Androidがキャッシュプロセスを残すことと、定期処理が動き続けることは区別します。

## 検証

`./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease :app:spotlessCheck`

従来のDigi Police・キャッシュ向け19件のJVMテストは、OFF/ON/不明設定の判定、サービス所有権、再開始、遅れた登録、二重停止、解除失敗の分離、APK世代ごとに一度だけの解析、同時取得、失敗・中断・破損時の再探索防止を検証します。実機の結果は`testing.md`に別途記録します。OS全体のmAh改善率はこれだけでは証明しません。

## 設定の可視性

対象にはモジュールの設定URIだけをREAD権限として渡し、Androidのパッケージ可視性も確保します。Providerは別途、導入済み対象UIDを照合します。設定アクセスはモジュール起動・更新・bootの単発受信で更新します。アクセスが失われた対象からは、共有された送信UIDを確認する明示的な要求と1回の応答で復旧し、繰り返しタイマーは使いません。任意のパッケージや任意のURIを外部から指定する機能はありません。

## FGS起動の回帰

実機の連続外部起動で、startForegroundを省いて即stopSelfにするとForegroundServiceDidNotStartInTimeExceptionが発生した。試行中の版は公開せず、外部起動では純正のforeground準備を完了してから停止する方式へ修正した。設定OFFイベントで止める対象も、純正onStartCommandが完了した実体に限る。位置登録の追跡・解除はその実体にだけ対応付ける。修正後の複数回起動と12秒の待機で、同一PIDの維持とGPS停止を確認した。

追加機能を含めたJVMテストは49件です。Android 16/17のAPK照合・模擬通信・UI確認は[0.2.0の検証](testing-0.2.0.md)へ記録します。

0.3.0のJVMテストは68件です。追加の所有関係・取得分の返却と、Android 16/17の実APK照合・実タイマー停止検証は[0.3.0の検証](testing-0.3.0.md)へ記録します。
