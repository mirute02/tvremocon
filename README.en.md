# TV Remocon

[日本語](README.md) | **English**

An Android home-screen widget that drives a TV through a TP-Link Tapo infrared hub. All
traffic stays on the LAN; nothing goes through the cloud. **Written, built and released
entirely on the phone, in Termux.**

## What it does

Pressing a key on the widget sends an IR command from the hub. The Tapo app never has to be
opened.

The widget takes **three shapes depending on its size**. An app cannot resize its own widget
on Android — `AppWidgetManager` only lets a launcher report the size it was given, never the
reverse — so choosing a shape means resizing the widget by hand.

| Shape | Keys |
|---|---|
| Small (squashed flat) | Power, CH▲, CH▼. No d-pad |
| Landscape (default) | A short menu on the left (power, mute, input, terrestrial, volume, channel), the 1-12 channel pad on the right |
| Large (stretched tall) | The whole remote: 41 keys, including the coloured programme keys, d-pad, subtitles and data broadcast |

**It rests dimmed and takes one tap to wake.** A widget on a home screen lives under a thumb,
so a resting state sits between an accidental brush and the TV. Once woken it stays live for
five seconds, extended by each press.

Key groups look different on purpose. Volume and channel are **square and the most muted**
things on the widget — they are pressed repeatedly and by feel, so putting the loudest colour
on the least deliberate keys would be backwards. The channel digits are **round and
blue-tinted**, because you aim at exactly one. The power key is the only red thing there.

## Requirements

- A Tapo infrared hub (H110; the unit this was developed against ships in Japan as **TH11**)
- **Third-Party Compatibility enabled** in the Tapo app. With it off the hub does not answer
  over KLAP and the handshake gets nowhere
- A TV remote already registered on the hub through the Tapo app
- Android 12 or newer (`minSdk 31`, needed for size-dependent widget layouts)

## Install

**A built APK is on the [Releases page](https://github.com/mirute02/tvremocon/releases).**
It is not on the Play Store.

1. Download the latest `tvremocon.apk` from Releases and open it
2. Android will ask you to allow installs from your browser (or files app)
3. On first launch, grant **local network access** (Android 17 and later)

To follow updates, add this repository's URL to
[Obtainium](https://github.com/ImranR98/Obtainium) and it will notify you when a new APK is
published.

The APK is signed with a dedicated key. **Android only allows an upgrade in place if the
signature matches**, so a build of your own and one from Releases cannot replace each other
without uninstalling first. Each release note carries the certificate's SHA-256.

To build it yourself, see [Building](#building).

## Setup

1. Open the app → **ハブと認証情報の設定** (hub and credentials)
2. **ハブを探す** scans the LAN, or type the IP directly
3. Enter your TP-Link account email and password, and save
4. Long-press the home screen → Widgets → **TV Remocon**
5. Choose which remote it drives — the hub and credentials are already stored, so that is all
   a new widget asks for

### Keeping the password out of the app

The hub has no local password of its own. It checks a hash derived from the TP-Link **cloud
account** — `python-kasa` documents the same field as "of the cloud account" — so the account
cannot be avoided. Handing the app the password can be.

```bash
java -cp tools/json.jar tools/KlapProbe.java hash
```

Paste the 64 hex characters into the authHash field during setup and leave the email and
password blank.

> That hash is password-equivalent, and the derivation is `SHA256(SHA1(user) || SHA1(pass))` —
> no salt, no stretching. If it leaks, brute-forcing the account password offline is
> practical, so treat it exactly like the password.

## Design notes

### An IR send is not retried

To avoid a physical action happening twice — volume jumping two steps — automatic retries are
suppressed **at the app layer, the KLAP layer and the HTTP layer**. OkHttp is built with
`retryOnConnectionFailure(false)`, and connections are not reused, because writing to a socket
the hub had already closed failed instantly and ate presses.

Outcomes are reported as four states, because **"no answer" and "the TV did not move" are
different facts**:

| Shown | Meaning |
|---|---|
| Not sent | The problem was known before anything left the device — no Wi-Fi, no permission, not configured, hub unreachable |
| Hub accepted | The hub acknowledged `sendIrCmdById` |
| Outcome unknown | The response was lost after sending. **Not reported as a failure** — the hub may well have transmitted |
| Hub rejected | An explicit error code came back |

Calling the third case a failure would invite a second press for a command already sent.

### Errors hide deep in the response

A failing child device still returns `error_code: 0` at the top level, so the whole response
is walked recursively looking for `error_code` / `errorCode`. And **finding no error is not
the same as success** — an acknowledgement is only accepted when it matches the exact shape a
real hub returns.

### Labels come from protocol names, not display names

`display_name` arrives truncated to four bytes for keys downloaded from TP-Link's database.
`POWER` becomes `POWE`, and `NAVIGATE_UP` / `DOWN` / `LEFT` / `RIGHT` all become `NAVI`, which
would make the arrow keys indistinguishable. Only user-recorded keys (`id == -1`) carry a full
string. Details in [docs/phase0-findings.md](docs/phase0-findings.md) (Japanese).

### Known behaviour that cannot be fixed here

**This TV moves volume two steps per press.** The logs show the app sending exactly one
request per tap, and the Tapo app produces the same two-step jump. It happens in the hub or
the TV's reading of the IR frame.

## Layout

```
transport/           hub transport, independent of the encryption scheme
  klap/              KLAP v2 (key derivation, AES-CBC, handshake)
ir/                  the hub's IR API (remote list, key send)
widget/              home-screen widget and its layout definitions
ui/                  setup screens
net/                 Wi-Fi pinning, endpoint restriction, hub discovery
tools/KlapProbe.java single-file connectivity probe, no build required
```

The boundary at `transport/` exists so that a future move to TPAP would land there rather
than spreading upward.

## Building

Everything runs on the phone; measured figures are in
[docs/toolchain.md](docs/toolchain.md) (Japanese).

```bash
studio build ~/src/tvremocon                    # APK
studio gradle ~/src/tvremocon testDebugUnitTest # unit tests
studio run   ~/src/tvremocon --logcat           # install and launch
```

The first build takes about two minutes while dependencies download; later ones about twenty
seconds. Avoiding Jetpack Compose is what keeps that number down on-device.

## Notes for contributors

- **Do not commit `tools/fixtures/`** — it holds a real hub's `device_id` and MAC. It is in
  `.gitignore`
- `tools/klap_vectors.json` is generated from fake credentials and is safe to commit
- Keep authHash, passwords and session cookies out of the logs

## Licence and credits

This repository is [MIT](LICENSE). Everything referenced or adapted, and the terms it came
under, is recorded in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

What the security model does and does not cover is in
[docs/security.md](docs/security.md) (Japanese).

This is not a published TP-Link API. A firmware update could stop it working.
