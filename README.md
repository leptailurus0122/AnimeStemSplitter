# アニメ音声分離（Android 版）

動画・音声ファイルを **セリフ / BGM / 効果音** の3トラックに分ける Android アプリです。
処理はすべてスマホの中で行い、ネットには送りません。

- 分離モデル: BandIt Plus（映画音響向けの「セリフ・音楽・効果音」分離モデル）を ONNX に変換して同梱
- 対応: Android 10 以降（arm64）。Pixel 10 Pro Fold の開いた画面では2列表示
- 入力: mp4 / mkv / webm / m4a / mp3 / wav など（端末が再生できる形式）。5.1ch はステレオにまとめます
- 出力: 44.1kHz / 16bit の WAV ×3。「ミュージック/アニメ音声分離」へ保存、または共有

## できること

- 「ファイルを選ぶ」または、ファイルアプリ・フォトの **共有** から送るだけで分離開始
- 処理は通知つきでバックグラウンド実行（画面を閉じても続く・通知からキャンセル可）
- 3トラックを完全同期で再生。音量・ソロ・ミュートで聴き比べ
- 過去の分離結果の一覧（タップで開く・削除）

## APK の作り方

### A. GitHub Actions（PC 不要・おすすめ）

このフォルダを GitHub のリポジトリ（非公開でOK）に push すると、自動で
1. 学習済みモデルを取得してスマホ用（ONNX）に変換し、元のモデルと結果が一致するか検証
2. 単体テスト
3. APK をビルド

まで行い、**Releases** に `AnimeStemSplitter.apk` が置かれます。スマホのブラウザで GitHub にログインしてダウンロードし、インストールしてください（初回は「提供元不明のアプリ」の許可が必要です）。

### B. 自分の PC でビルド

```bash
pip install torch torchaudio onnx onnxruntime numpy pyyaml librosa
python tools/export_onnx.py          # app/src/main/assets/ にモデルができる（約190MB）
./gradlew assembleRelease            # Android SDK が必要（Android Studio で開いても可）
```

`app/build/outputs/apk/release/app-release.apk` ができます。

## しくみ

```
ファイル → MediaCodec でデコード → 44.1kHz に変換（sinc 補間）→ 一時ファイル
        → 6秒ずつ（1/4 ずつ重ねて）ONNX Runtime で分離 → フェードで重ね合わせ → WAV ×3
```

- STFT / 逆STFT もモデル内で行うため、アプリは波形を渡すだけ
- 長い動画でもメモリをほとんど使わないストリーミング処理（1話 24 分で一時ファイル約 500MB、結果 WAV 約 750MB）
- 分離の計算は PC 版（Python）と同じで、単体テストで一致を確認しています

| フォルダ | 内容 |
|---|---|
| `app/src/main/java/.../engine` | デコード・リサンプル・分離・WAV |
| `app/src/main/java/.../service` | バックグラウンド処理（通知） |
| `app/src/main/java/.../player` | 3トラック同期ミキサー |
| `app/src/main/java/.../ui` | 画面（Jetpack Compose） |
| `tools/export_onnx.py` | モデル変換と検証 |

## 注意

- 処理速度は端末の CPU 次第です。1話まるごとだと長時間かかり、本体が温かくなります。まずは短いクリップでお試しください
- Android 15 以降、バックグラウンドのメディア処理は1日あたり合計6時間までです
- `app/signing.keystore` は個人インストール用の署名鍵です（Play ストアには使わないでください）
- 分離した音源は個人での視聴・研究など私的利用の範囲でお使いください

## クレジット

- BandIt: Karn N. Watcharasupat ほか（Apache-2.0）— `tools/bandit/LICENSE_BANDIT`
- 実装・学習済み重み: ZFTurbo / Music-Source-Separation-Training（MIT）— `tools/bandit/LICENSE_MSS`
- ONNX Runtime（MIT）
