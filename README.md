# fafnirx-android

Android client for [fafnirx](https://github.com/jameelhamdan/fafnirx). It backs up folders you choose to your fafnirx master, the
way the Windows/macOS/Linux client does. See [spec.md](spec.md) for the design.

- **Read only.** The app asks for a read grant on each folder in the system folder picker and declares no storage permission. It
  cannot change, create or delete anything on the phone, and it never deletes anything on the master.
- **Small and native.** Kotlin on the Android SDK, no AndroidX or UI framework. The only libraries are gRPC (lite, OkHttp
  transport) and protobuf-lite. The APK is about 300 KB.
- **Sync switch.** Settings (the Server screen) has a **Sync files** switch: off means nothing is uploaded, in the background or
  with Sync now, and a running sync stops at once. It takes effect without pressing Save.
- **Hashes while it uploads.** One thread reads and hashes the next files while up to 3 uploads run at once.
- **Three screens.** Status (synced / waiting / failed, and whether the phone or the server is offline), Folders, Server.

## Use

1. In the fafnirx web app: Devices, add a device, copy the token (shown once).
2. Install the APK (from the releases page), open it, enter the server address (`192.168.1.10:7443`, or `https://...`) and the
   token, press **Test connection**, then **Save**.
3. **Folders**, **+ Add folder**, pick e.g. `DCIM/Camera`. Android does not let apps pick the storage root or `Download`; pick a
   folder inside them.
4. It syncs about every 15 minutes (Wi-Fi only by default, and 2 files uploaded at a time; both can be changed on the Server screen), or press **Sync now** on Status.

A link `fafnirx://enroll?server=...&token=...` fills the Server screen.

Notes: the background run needs a network that Android considers connected to the internet; on a Wi-Fi without internet use
**Sync now**, which works on any network. The master lists a phone as offline between syncs (it only sends heartbeats while syncing); a renamed or moved file is
uploaded again under its new path (no data is sent if the master already has the content) and the old path stays on the master.

## Build (Docker only)

No JDK, Android SDK or Gradle on your machine, only Docker:

```sh
./build.sh          # tests + signed APK  -> dist/fafnirx-android-<version>.apk (+ .sha256)
./build.sh test     # unit tests only
./build.sh keygen   # show the signing key (made on first build in .signing/)
```

The APK is signed with a self-signed key made on the first build and
kept in `.signing/` (git-ignored), so later builds update over earlier ones. Keep that file.

## GitHub workflow

`.github/workflows/build.yml` runs the same Docker build on every push and pull request and uploads the APK. Pushing a tag
`v*` also creates a release with the APK. For updates to install over each other, store the key once: run `./build.sh keygen`
and add the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`.

## Tests

`./build.sh test` runs, in Docker: BLAKE3 against the reference, file root hashes against the server's formula, the upload against
a fake master (in process and over TCP), path rules, the status model, and a guard that fails the build if the sources or the
manifest could write to storage. `MasterE2ETest` uploads to a real master when `FAFNIRX_E2E_SERVER` and `FAFNIRX_E2E_TOKEN` are set.

The device contract is `app/src/main/proto/fafnirx.proto`, copied from the server repo (`scripts/sync-proto.sh`).
