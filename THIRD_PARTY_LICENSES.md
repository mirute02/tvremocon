# サードパーティ ライセンス

このプロジェクト自体は MIT（[LICENSE](LICENSE)）。以下は参照・翻案した外部実装と、その許諾条件。

## Loadst0ne/tapo-ir-hub (MIT)

https://github.com/Loadst0ne/tapo-ir-hub

`custom_components/tapo_ir/protocol.py` の再帰的エラー判定を Kotlin に翻案し、
`app/src/main/java/com/tvremocon/transport/SmartEnvelope.kt` の `validate()` として実装している。
また H110 の IR プロトコル（`control_child` / `requestData` / `sendIrCmdById` の形、
`ir.remote` の絞り込み、送信系を再送しない方針）はこの実装から読み取った。

翻案して取り込んでいる以上、名前を挙げるだけでは足りないため、許諾文を全文保持する:

```
MIT License

Copyright (c) 2026 Loadst0ne

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## mihai-dinculescu/tapo (MIT)

https://github.com/mihai-dinculescu/tapo

KLAP v2 の鍵導出とハンドシェイクの仕様確認に参照した。コードは取り込んでいないが、
`app/src/main/java/com/tvremocon/transport/klap/KlapSession.kt` の実装はこの記述と
仕様書の内容を突き合わせて書いている。

## python-kasa/python-kasa

https://github.com/python-kasa/python-kasa

SMART エンベロープの形（`request_time_milis` の綴り、`terminal_uuid` の生成）と、
資格情報がクラウドアカウントのものであることの確認に参照した。コードは取り込んでいない。

## poordevcode/termux-android-studio (Apache-2.0)

https://github.com/poordevcode/termux-android-studio

`studio new --xml` が生成した Android プロジェクトの雛形（Gradle 設定、テーマ、ランチャー
アイコンのベクタ）を出発点にしている。生成物は Android Studio の標準的な雛形に相当するもので、
アプリのコードは全て書き下ろしだが、出所として記載しておく。

## 取り込んでいないもの

- **petretiandrea/plugp100 (GPL-3.0)** — `control_child` のパラメータ名の確認に読んだのみ。
  コードを取り込むとこのアプリ全体が GPL になるため、一切コピーしていない。
- **IMMINJU/lantern** — LICENSE が存在せず（GitHub API も `license: null`）、
  既定では全権利留保のため流用していない。KLAP 層は自前実装。
