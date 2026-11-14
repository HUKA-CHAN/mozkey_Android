# Mozkey for Android (Android 17 対応 IME)

Mozkey の変換エンジンを Android の日本語キーボードとして使うためのアプリです。
upstream Mozc の Android クライアントは廃止済みで、このリポジトリにも
`libmozc.so` の JNI しか残っていなかったため、アプリ部分は新規実装です。

- `targetSdk` / `compileSdk` 37 (Android 17)、`minSdk` 30 (Android 11)
- 12 キーのフリック入力と QWERTY ローマ字入力(設定で切り替え)
- 英字・数字記号キーボード(直接入力)
- ライブ変換と Zenz 補正(端末内で llama.cpp を実行)
- 権限なし。`INTERNET` 権限も持たないため、入力内容は端末の外に出ません

## 構成

```
android/                      Gradle プロジェクト
  app/src/main/java/          IME 本体 (Kotlin、依存は protobuf-javalite のみ)
  app/src/main/cpp/           libmozkey_zenz.so (llama.cpp + Zenz バックエンド)
  scripts/prepare_native.sh   ネイティブ成果物の準備
src/session/zenz_inprocess_client.{h,cc}   Android 用 Zenz トランスポート
src/zenz/zenz_inprocess_abi.h              libmozc.so と Zenz ランタイム間の C ABI
```

### Zenz の動かし方

Windows / macOS では `mozc_zenz_scorer` と `llama-server` を別プロセスで起動し、
名前付きパイプや Unix ドメインソケットで通信します。Android の IME はヘルパー
プロセスを起動できないので、同じプロセス内で llama.cpp を動かします。

1. `libmozc.so` の `CreateZenzClient()` は Android では `ZenzInProcessClient` を返す
2. `libmozkey_zenz.so` がモデルを読み込み、`mozkey_zenz_register_backend()` で
   推論関数を `libmozc.so` に登録する
3. 以降はデスクトップ版と同じ `ZenzLiveCorrector` がプロンプト生成・採用判定を行う

推論の条件は `llama-server` への `/completion` リクエストと同じです
(greedy、`cache_prompt` 相当の KV キャッシュ再利用、U+EE00〜U+EE0F と改行で停止)。
llama.cpp のコミット、トークナイザーパッチ、モデルは
`src/zenz_runtime/llama_runtime.lock.json` の固定値をそのまま使います。

### ライブ変換

セッションが `Output.callback` で「N ミリ秒後にこのコマンドを送れ」と指示して
きます (`APPLY_LIVE_CONVERSION` / `APPLY_ZENZ_LIVE_CORRECTION`)。Android 側は
この汎用コールバックに従うだけで、遅延・世代管理はすべてセッション側です。

## Android 17 (API 37) への対応点

| 変更点 | 対応 |
| --- | --- |
| CJKV IME のアクセシビリティ | 未確定文字列を設定するとき `TextAttribute.Builder.setTextSuggestionSelected()` で「変換候補を選択中か」を通知 |
| Safer Native DCL | ネイティブライブラリは APK 内のものを `System.loadLibrary()` で読むだけ。書き込み可能な場所からは読み込まない |
| アプリのメモリ上限 | `onTrimMemory()` / `onLowMemory()` で Zenz モデルを解放し、次の入力開始時に再読み込み |
| ローカルネットワーク権限、ECH、証明書の透明性 | ネットワークを使わないため対象外 |
| 16 KB ページサイズ (Android 15〜) | `libmozc.so` / `libmozkey_zenz.so` とも `max-page-size=16384` でリンク |
| edge-to-edge の強制 (Android 15〜、API 36 以降は解除不可) | キーボードと設定画面の両方で `WindowInsets` を見てナビゲーションバー・切り欠きを避ける |

## ビルド

### GitHub Actions (推奨)

フォークしたリポジトリにこの変更を push すると `Android APK (Mozkey IME)`
ワークフローが動き、`mozkey-android-apk` という成果物に APK が入ります。
Actions タブから手動実行 (`workflow_dispatch`) もできます。

### ローカル (Linux / macOS / WSL)

必要なもの: bazelisk、PowerShell 7、Python 3.12 以降、JDK 17 以降、Gradle 9.5.0 以降、
Android SDK (API 37 のプラットフォームと NDK は Gradle が自動取得します)。

```
pwsh ./tools/dictionary/prepare_daily_dictionary.ps1   # Mozkey の辞書入力を生成 (PowerShell 7)
android/scripts/prepare_native.sh        # libmozc.so、辞書、protobuf、llama.cpp、モデル
gradle -p android :app:assembleRelease
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

`prepare_native.sh` は `mozc` `proto` `llama` `model` の各ステップを個別に実行
できます。Kotlin だけ変更したときは再実行不要です。

リリース APK はデバッグ鍵で署名しています。配布する場合は
`android/app/build.gradle.kts` の `signingConfig` を自分の鍵に差し替えてください。

## 使い方

1. アプリ「Mozkey」を開き、「キーボードを有効にする」→「Mozkey に切り替える」
2. 🌐 キーで他のキーボードへ切り替え(長押しで一覧)
3. 候補バー左端の点が Zenz の状態です(黄土色 = 処理中、青緑 = 適用済み)

## 現時点の制限

- 物理キーボードでのかな入力には未対応です(キーはアプリへそのまま渡ります)
- 候補は横スクロールの 1 行表示のみ。ユーザー辞書の編集画面はありません
- Zenz の文脈は入力開始時点のカーソル前後 64 文字です
- Mozkey のデスクトップ向け機能(縦書き UI、ルビ表示、外観カスタマイズなど)は
  Android 版にはありません
