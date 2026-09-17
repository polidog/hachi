# Hachi

Echo Show 5 (2nd gen) を、Gemini Live API と喋る壁掛けの音声アシスタントにするアプリ。LineageOS 18.1 (Android 11) の上で動く。サーバーは持たず、端末から各サービスへ直接つなぐ。

作りかけ。いま動くのはここまで:

- 時計と、時刻に追従する空のグラデーション
- Gemini Live API との音声会話（字幕つき）と、`usageMetadata` からの料金計上・日次上限
- 天気ページ（Open-Meteo）と雨レーダーページ（Yahoo! 気象情報 API）
- 道具: 時刻と天気（Gemini の function calling）
- 設定画面（API キーは Android Keystore で暗号化して保管）

これからやること、順番、その理由は [docs/roadmap.html](docs/roadmap.html)。次は Home Assistant の MCP サーバに繋いで、機器と音楽を声で動かせるようにする。

## ドキュメント

HTML なので、GitHub 上では raw で落とすかブラウザで開く。

- [docs/architecture.html](docs/architecture.html) — アーキテクチャ規約。構成、依存の向き、端末固有の制約（エコーキャンセラが無い話など）
- [docs/roadmap.html](docs/roadmap.html) — 実装計画。手順表と、やらないと決めたもの
- [docs/test-architecture.html](docs/test-architecture.html) — テスト方針。何を書いて何を書かないか

## ビルド

```sh
mise exec -- ./gradlew testDebugUnitTest assembleDebug
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK は mise で管理している（`mise.toml`）。ABI は `armeabi-v7a` のみ。

API キーは端末の設定画面から入れる。長い文字列は開発機から流し込める:

```sh
adb shell am broadcast -a dev.polidog.hachi.SET -e name geminiKey -e value '...'
```

ログは全ファイル共通で `Hachi` タグ:

```sh
adb logcat -s Hachi
```

## 断り書き

一台の端末のためだけに書いている個人プロジェクト。画面サイズ (960x480)・ABI・Android バージョンは決め打ちで、汎用化していない。

[syumai/butler](https://github.com/syumai/butler) に着想を得ている。同じ端末で動く、OpenAI Realtime API 版の先行プロジェクト。

## ライセンス

MIT
