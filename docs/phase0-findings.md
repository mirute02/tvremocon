# Phase 0 実測結果（2026-09-06）

Termux 上の `tools/KlapProbe.java` で実機に対して確認した事実。Kotlin 実装はこれを正とする。

## ハブ

| 項目 | 値 |
|---|---|
| host | `192.168.1.4`（tcp/80。LAN 内で唯一の KLAP 候補） |
| model | **`TH11`**（`H110` ではない。日本向け型番表記と思われる） |
| device_type | `SMART.TAPOHUB` |
| fw_ver | `1.5.4 Build 260725 Rel.042648` / hw_ver `1.0` |
| device_id | `<hub-device-id>` |
| セッション | `TP_SESSIONID` + `TIMEOUT=86400`（24h。仕様書通り） |

**KLAP v2 で確定**（handshake1 の deviceHash 照合 → handshake2 → `get_device_info` が通った）。
48 バイト応答だけでは v1 と区別できないので、モデル判定には認証まで通すこと。

→ **実装では model を `H110` 決め打ちで検証しない。** `device_type == "SMART.TAPOHUB"` と
`ir.remote` の子が取れることを条件にする。

## 子デバイス（`get_child_device_list`、4件・1ページ）

| nickname | category | model | remote_type | remote_id | key_sum | device_id |
|---|---|---|---|---|---|---|
| **TV** | `ir.remote` | `TV` | 1 | 4305 | 143 | `…<hub-device-id>030B0001` |
| エアコン | `ir.remote` | `AC` | 2 | 8980 | 0 | `…<hub-device-id>030B0002` |
| ライト | `ir.remote` | `Light` | 1 | 0 | 4 | `…<hub-device-id>030B0003` |
| （温湿度センサー） | `subg.trigger.temp-hmdt-sensor` | — | — | — | — | — |

子の `device_id` は **ハブの device_id + 8桁のサフィックス**。
`category != "ir.remote"` を除外する実装は正しく機能した。

## `key_list[]` — 仕様書と違う点

ダウンロード済みキーと自作キーでフィールドが違う。

```
ダウンロード済み (id >= 0): name, id, display_name, pwm          ← order/type/icon なし
自作キー        (id == -1): name, id, display_name, pwm, order, type, icon
```

**`display_name` はダウンロード済みキーでは壊れている。**
Base64 を復号すると 4 バイト固定バッファの中身が出てくる:

| name | display_name(復号後) |
|---|---|
| `POWER` | `POWE` |
| `OK` | `OK\0\0` |
| `CH+` | `CH+\0` |
| `NAVIGATE_UP` / `NAVIGATE_DOWN` / … | 全部 `NAVI`（区別不能） |
| `PeF1691`（自作・id=-1） | `関節` ← 正しい |

→ **ラベルは `name` を使う。`display_name` は `id == -1` のときだけ採用**し、
それ以外は無視する。復号後の NUL バイトは除去する。

`pwm` は int（`24` / `26`）で、生の IR 波形ではなく搬送波の指標らしい。`pulse` は返ってこない。

## TV リモコンの主要キー（`name` が送信に使う識別子）

```
POWER  MUTE  INFO  MENU  POPMENU  GUIDE  EXIT  BACK  LAST  HOMEPAGE
VOL+  VOL-  CH+  CH-
OK  NAVIGATE_UP  NAVIGATE_DOWN  NAVIGATE_LEFT  NAVIGATE_RIGHT
0 1 2 3 4 5 6 7 8 9 11 12  +100  -/--
INPUT  INPUT 1  INPUT 2  INPUT 3  HDMI1  HDMI2  HDMI3
BS  CS  BS1..BS12  CS1..CS12  TV/DTV  ATV  Terrestrial Digital  Radio; TV/Radio
PLAY  PAUSE  STOP  REWIND  FAST_FORWARD  RECORD  Skip Reverse  Quick Skip  Instant Replay
RED  GREEN  BLUE  YELLOW  Subtitles  DATA  D-Data  PICTURE MODE  Format (Aspect)  SLEEP
```
全 143 キーは `tools/fixtures/remotes.json`（gitignore 済み）。

## 送信 — `sendIrCmdById` は通る

`INFO` を 1 回送信 → **テレビにメニューが 1 回表示された**（再送なしを実地で確認）。

リクエスト（`multipleRequest` 包み）:
```json
{"method":"control_child","params":{"device_id":"…030B0001",
  "requestData":{"method":"multipleRequest",
    "params":{"requests":[{"method":"sendIrCmdById","params":{"name":"INFO"}}]}}}}
```

**応答（確定形）**:
```json
{"error_code":0,
 "result":{"responseData":{"result":{"responses":[
   {"method":"sendIrCmdById","error_code":0}]}}}}
```

→ 「ハブ受付済み(ACCEPTED)」の判定条件はこれで確定:
`result.responseData` を起点に `responses` まで降り、`responses[0].method == "sendIrCmdById"` かつ
`error_code` が存在して 0。**この形を満たさなければ `UNKNOWN`**（空 `{}` を成功にしない）。

外側エンベロープには `request_time_milis` と `terminal_uuid` を入れて送ったが問題なし
（任意だが、送っても拒否されない）。

## 未確認

- `--no-batch`（`multipleRequest` で包まない形）が通るか — 仕様書§5-3 の検証。IR がもう一度飛ぶので未実施
- ページング（子が 1 ページに収まったため未通過）
- セッション期限切れ後の再ハンドシェイク
