# ビルド環境（Phase 1a 実測 / 2026-09-06）

Termux 上で完結。Android Studio も PC も使わない。

## 実際に使われたバージョン

`studio build` のログと各コマンドの `--version` から確認したもの。
`gradle-wrapper.properties` の記載値ではない（そもそも wrapper は生成されていない）。

| 項目 | 値 |
|---|---|
| Gradle | **9.7.1**（Termux の system gradle。`studio` が使う） |
| Gradle 同梱 Kotlin | 2.4.0 |
| JDK | **OpenJDK 21.0.12**（Termux。`org.gradle.java.home` で daemon に強制） |
| AGP | **9.2.1**（内蔵 Kotlin プラグイン。`build.gradle` に記載） |
| compileSdk / targetSdk | 37 / 37 |
| Java source/target / jvmTarget | 17 |
| aapt2 | `~/android-sdk/build-tools/36.0.0/aapt2`（termux-studio 自前ビルドの aarch64 版。`android.aapt2FromMavenOverride` で指定） |
| termux-studio | commit `ac04037`（2026-07-13） |

`~/.gradle/gradle.properties` は termux-studio の install.sh が生成したもので、
`aapt2FromMavenOverride` / `org.gradle.java.home` / 並列・キャッシュ設定が入っている。
プロジェクト側の gradle 設定は Android Studio 互換のまま触らない。

## ビルド時間（実測）

| 状況 | 時間 |
|---|---|
| 初回（依存ダウンロード込み、`~/.gradle/caches` 空から） | **2分8秒** |
| 2回目以降（キャッシュあり） | **20秒** |

Jetpack Compose を使わない判断はこの時間を維持するため。

## コマンド

```bash
export PATH="$HOME/termux-studio/bin:$PATH"

studio build ~/src/tvremocon                       # :app:assembleDebug
studio gradle ~/src/tvremocon testDebugUnitTest    # JVM 単体テスト
studio run   ~/src/tvremocon [--logcat]            # ビルド→インストール→起動
studio adb pair                                    # 無線デバッグの初回ペアリング（logcat に必要）
```

## 注意

- `studio new` は**生成先が空でないと停止する**（`.git` だけでも該当）。
  そのため Phase 1a は「scratchpad で生成 → 無改変ビルド → リポジトリへ tar でコピー」の順で行った
- `local.properties`（SDK パス）と `.kotlin/` は端末固有なので `.gitignore` 済み。
  他の環境では `studio load <path>` が再生成する
- Gradle 10 で消える非推奨機能の警告が出るが、ビルド自体は成功している
