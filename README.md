# Oura Arena Sync

Xiaomi Smart Band等のヘルスデータをHealth Connect経由で読み取り、[Oura Arena](https://oura-arena.vercel.app)ダッシュボードに送信するAndroidアプリ。

## 仕組み

```
Xiaomi Smart Band → Mi Fitness → Health Connect → このアプリ → Oura Arena API
```

## 対応データ

- 睡眠（ステージ、就寝/起床時刻、効率）
- 歩数
- カロリー（アクティブ/合計）
- 心拍（平均/安静時）
- SpO2（対応デバイスのみ）

## セットアップ

1. APKをダウンロードしてインストール
2. Health Connectの権限を許可
3. サーバーURL: `https://oura-arena.vercel.app`
4. APIキー: 管理者から受け取ったキーを入力
5. 「今すぐ同期」で動作確認
6. 自動同期をONにする（15分間隔）

## 要件

- Android 9.0+ (API 28)
- Health Connect アプリがインストール済み
- Mi Fitness → Health Connect の同期が有効

## ビルド

```bash
./gradlew assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に出力。
