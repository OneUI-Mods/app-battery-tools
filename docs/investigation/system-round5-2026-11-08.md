# システム軽量化・第5回

## 調査順序

1. Samsungの表示調整で増えるカメラ用待機スレッドを補正する。
2. 無改造でも発生するスクリーンショット撮影直後の高負荷を、純正の取得・圧縮・保存・画像解析から調べる。
3. 以上の後に、Systemのメモリ使用量を内訳から調べ、重複・不要な保持を減らす。全アプリの強制終了やキャッシュ消去を省メモリ対策として繰り返さない。

## 表示調整（EnvironmentAdaptiveDisplay / SEAD）

実機の同一PIDで638本のスレッドを確認し、そのうち602本がCameraDeviceExecutor（/procの表示ではCameraDeviceExe）だった。後の観測では22本になり、その22本はすべてS状態・futex_wait・累積user/system ticks 0だった。アプリを再起動せず減っているため、恒久的なリークや、その602本がCPUを常時占有していたとは主張しない。

実機から取得したframework.jarでは、CameraDeviceImplがデバイスごとに専用のnewSingleThreadExecutorを作る。closeではオフライン切り替え用executorをshutdownするが、このデバイス用executorはshutdownしていない。同じ実機のcore-oj.jarでは、newSingleThreadExecutorの実体はcore/max=1、keepAlive=0、無制限LinkedBlockingQueueのThreadPoolExecutorであり、ラッパーのCleanerがGCによる到達不能の検出後にshutdownする。従って、スレッドの回収をGCに委ねたまま、多数が待機する余地がある。22本への減少時にGCを直接記録したわけではないため、その減少の原因自体は推定である。

### 0.5.0の補正

SEAD自身のCameraDevice.StateCallback.onClosedの正常完了後だけ、閉じたCameraDeviceの専用キューを確認する。framework側で専用ThreadFactory・生成メソッド・executorフィールド・closeからの使用をDexKitで照合し、実行時にもThreadFactoryが同じ実体であることを必須にする。

初期設定がcore/max=1・keepAlive=0・core timeoutなしの場合だけ、keepAliveを1秒にしてcore timeoutを許可する。shutdown、キャンセル、割り込み、キューの破棄、並列数の変更はしない。予約済みコールバックはそのまま実行し、後から届く処理は同じexecutorがスレッドを作り直して受け付ける。測定頻度やカメラを開いている間の実行条件は変更しない。他アプリ、system_server、一般のスレッドプールは対象外。

既に独自のタイムアウトがあるもの、別ThreadFactory、別の並列数、終了済み、不明・曖昧なラッパーでは変更しない。起動時にタスクを投入しない空のexecutorでアクセス可否だけを確認し、見えない状態をactiveと報告しない。

アプリの世代に加え、OSのfingerprintとframework.jarのパス・長さ・更新時刻もキャッシュ世代へ含める。他の12対象の世代は維持する。探索は初回・更新後だけであり、onClosed内でDEX探索はしない。該当する専用キューの証拠がないOSではフックを入れずunsupportedを表示する。Android 16/17の既存機能は維持する。

### 効果の限界

現時点の主対象はスレッド・スタック等の残留とピーク時メモリであり、消灯中の電池削減量や全体のFPS改善率は未測定。OSが既に回収していれば、その分の効果は出ない。スレッド数に固定の1本あたりメモリを掛けて削減量を作らない。

## 追加したシステムAPK調査

| 対象 | 確認した経路 | 判断 |
| --- | --- | --- |
| 表示調整 | カメラ終了、専用executor、GC/Cleanerに依存する回収 | 上記の狭い補正を追加 |
| Game Optimizing Service | 自動制御は初回60秒・以後10秒の予約。停止時にFuture.cancelとexecutor.shutdownあり。GFIにもTimer.cancelあり | 終了経路あり。ゲーム性能・温度制御の間隔を変更しない |
| デバイスケア | PowerShareセンサーの終了時にHandler解除とunregisterListenerあり | 給電・保護・最適化機能を一律停止する根拠なし |
| DiagMonAgent | 初期遅延の一部はAndroidのProfileInstaller。画面OFFの診断収集には許可・設定の検査あり | 遅延処理の存在だけで暴走と判断しない。現時点で変更しない |
| Wi-Fiインテリジェンス | DEX参照では学習用HandlerのWakeLock取得と解除を確認。選択した内部クラスは逆コンパイラーで見つからず、詳細な制御フローは未検証 | Wi-Fi切り替え・学習を止める根拠不足 |
| Samsungメール | WakeLockHelperは要求ID管理と解除があり、30秒の監視は保持中に使用。EnterpriseAuthenticationのTimerは15分後開始、20分周期で未使用サービスの接続解除を判定 | メール受信・認証・接続解除を停止しない。全所有者の異常終了まで確認済みとはしない |

6本すべてbase DEXの参照走査で読み取りエラーは0件。選択クラスや逆コンパイルの失敗、ネイティブ部分まで全解析した意味ではない。

## 現在の統計の扱い

28.63秒のCPU差分は画面ON・通常利用・ADB調査を含む。LINEが約8.23 CPU秒、SurfaceFlinger約3.59、system_server約2.15だった。ユーザーの利用中の負荷を、不要な待機消費と呼ばない。UID 1000の電池合計には多くのシステムパッケージが含まれ、SEADやデバイスケアへ丸ごと割り当てない。

メモリはPSS/RSS/SwapPSSを区別する。全体表示には負のLost RAM等の不整合があるため、その数値からメモリリーク量や削減可能量を計算しない。Systemのメモリ削減では個別プロセスの共有・匿名・スワップ・オブジェクト保持を確認する。

## スクリーンショット調査の開始状況

純正Samsungキャプチャ6.2.44.29を実機から取得。無改造でも起きるというユーザーの観測を前提にする。Capture Toolsの追加したウィンドウ制御と撮影許可の補正を読み、現時点でその経路には画像全体の再加工は見つかっていない。画像保存・OCR等の実行時間の特定はまだであり、未検証の処理を原因と断定しない。操作枠を確認して短いトレースを採り、撮影・保存・後処理を分ける。

生APK・framework/core-oj・ログ・トレースは非公開の`.workspace/system-lightweight-round5-20261108/`へ保存し、Gitへ含めない。

## base APKの同定

| package | version | SHA-256 |
| --- | --- | --- |
| `com.samsung.android.sead` | 17 | `20ef4eae9ea7ccd8b33677970a1ab53b2d5da6c7c0e59b1dc6845f585978576b` |
| `com.samsung.android.game.gos` | 4.4.00.30 | `e0e296f717c30a3fe29a0f3032819554a38b34a5d62a7af38a161b770da16f5c` |
| `com.samsung.android.lool` | 13.8.93.0 | `9a5f1547ae492dc992a017ff14472c39cfcdc0cc39572c63ebc1c426fc073245` |
| `com.sec.android.diagmonagent` | 9.7.06 | `f5ffe2bf4e7a1b9036ab8826a5da2abfd5e10fa7a2347525ec8cd98a1b67f640` |
| `com.samsung.android.wifi.intelligence` | 17 | `ffdcd2cb2ff9f5acc84aa77d9504bb6dc0b708ff2dc0c77f249dad2bcbbb93c4` |
| `com.samsung.android.email.provider` | 6.2.15.11 | `4aec49175c6f788780e634547cbde179016111ea88a7bd8414726cfa34f55668` |

## スクリーンショットの初回トレース

ユーザーが通常の方法で1回撮影した45秒のトレースを取得した。撮影・保存に加え、純正の文字認識・画像分類ワーカーが起動していた。

- ImageExporter_writeImageは約1358ms、ImageExporter_execute全体は約1430ms。
- SystemUIの撮影用プロセス、Samsungキャプチャのメイン、ondemandscreenshotworkerがそれぞれ起動した。CPU実行時間は順に約2.35秒、4.55秒、4.64秒。これらは記録内の集計で、同じ壁時計時間では並行して動く。
- キャプチャメインの1秒区間で約1.86 CPU秒、後続ワーカーの1秒区間で約1.93 CPU秒。後続の開始後にはOCR、MiniLM、TinyViT関連のクラス読み込みを確認した。読み込みと推論時間を同一視しない。
- 保存スレッドのCPUは約1369msだったが、画像書き込みだけのCPUと厳密には区切っていない。上記1358msは壁時計時間である。
- 主な後続負荷は撮影開始から約9〜13秒後にも見られた。保存が終わった後も別プロセスで処理が続く構成である。

rootで読んだlogcatには今回の撮影記録を取得できず、判定はPerfettoのslice・sched・開始プロセスと、撮影後のpsへ基づく。プロセス名の一部はzygote64/NULLのままだったため、固定名だけで集計せずPID・UID・実行スレッド名・APK読み込みを照合した。64MBのRING_BUFFERによりschedの冒頭約11秒は残っていない。撮影開始は約20秒時点であり、撮影区間は残っていた。冒頭欠落を0 CPUとして扱わず、45秒平均の負荷として解釈しない。FrameTimelineのinfoエラーはあり、フレーム品質の評価には使わない。

無改造でも起きるというユーザーの観測と整合する純正の処理を確認したが、今回の端末はモジュール有効の状態であり、全CPUを無改造時にも同量発生すると実証したわけではない。画像解析の重複や、機能を維持したまま減らせる処理は追加確認する。画質・保存内容・文字認識機能を一律に落とす補正はしていない。
