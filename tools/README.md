# tools/ — Phase 0 疎通確認

Android を書く前に、Termux 上の Java 単体ファイルで H110 へ直接 KLAP v2 を叩く。
アプリと同じ `javax.crypto` / `HttpURLConnection` を使うので、ここで通ればプロトコル理解は正しい。

## 準備（1回だけ）

```bash
curl -o tools/json.jar https://repo1.maven.org/maven2/org/json/json/20260814/json-20260814.jar
```

`tools/json.jar` と `tools/fixtures/`（実機由来の device_id 等）は `.gitignore` 済み。
`tools/klap_vectors.json` は架空資格情報から生成した固定ベクタで、コミットして良い。

## 使い方

資格情報は環境変数で渡す。`read -s` を使えば画面にもシェル履歴にも残らず、
シェルを閉じれば消える。`~/.tvremocon_credentials`（1行目メール、2行目パスワード）からも
読めるが、平文がディスクに残るので常用は勧めない。
メールアドレスは Tapo アプリに登録した通りの大文字小文字で入力する。

```bash
export TAPO_USER='you@example.com'
read -s TAPO_PASS && export TAPO_PASS

java -cp tools/json.jar tools/KlapProbe.java discover                 # KLAP 候補を探す
java -cp tools/json.jar tools/KlapProbe.java info    --host <hub-ip>   # 認証 + model 確定
java -cp tools/json.jar tools/KlapProbe.java remotes --host <hub-ip>   # → tools/fixtures/remotes.json
java -cp tools/json.jar tools/KlapProbe.java send    --host <hub-ip> --device-id <id> --key <name>
java -cp tools/json.jar tools/KlapProbe.java send    --host <hub-ip> --device-id <id> --key <name> --no-batch
java -cp tools/json.jar tools/KlapProbe.java vectors
```

## 結果の読み方

| 出力 | 意味 |
|---|---|
| `AUTH: device hash mismatch` | 資格情報の文字列がハブ側と一致しない。`--try-lowercase` で小文字版を切り分け |
| `handshake2 ok -> KLAP v2 confirmed` | v2 確定。48 バイト応答だけでは v1 と区別できない |
| `RESULT: ACCEPTED` | ハブが `sendIrCmdById` を受理（期待形の応答あり） |
| `RESULT: REJECTED` | ハブが明示的にエラーを返した（入れ子のどこかに `error_code != 0`） |
| `RESULT: UNKNOWN` | 応答喪失または形が想定外。**失敗と断定しない**。テレビの反応を確認 |

`send` は HTTP/KLAP のどの層でも再送しない。テレビが 2 回反応したらプロトコル側の問題ではなくハブ側の挙動。
