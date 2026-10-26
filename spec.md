# fafnirx-android: spec

Android client for [fafnirx](../replicator) (the server repo, `replicator`). The phone is one more **device**: it watches
folders the user picks, reads them, and sends new and changed files to the master. It follows the same one-way rule as the
desktop client: **read only on the phone, upload only to the master**. Nothing is ever written to, changed on, or deleted
from the phone, and nothing is ever deleted from the master (`MarkDeleted` is never called).

This replaces `PLAN.md` for v1 scope. Differences from it: folders are picked with the Storage Access Framework (not
MediaStore), there is no Compose, WorkManager or Room, gRPC uses the official lite library (`grpc-okhttp`) with stubs generated from the server's proto, and the viewer (v2) is dropped from this spec.

## 1. Requirements

| # | Requirement | How this spec meets it |
|---|---|---|
| R1 | Native code only, as lightweight as possible | Kotlin on the Android SDK. No cross-platform framework, no WebView, no AndroidX, no Compose. Dependencies are only the official gRPC lite stack (`grpc-okhttp`, `grpc-stub`, `grpc-protobuf-lite`, `protobuf-javalite`) and the Kotlin stdlib. Target APK < 4 MB after R8 (Â§3) |
| R2 | Build with Docker, nothing installed on the host | One pinned `Dockerfile` holds JDK, Android SDK and Gradle. `./build.sh` only calls `docker` (Â§8) |
| R3 | GitHub workflow that builds a self-signed APK | `.github/workflows/build.yml` runs the same Docker build, signs with a self-generated key, uploads the APK, attaches it to releases on tags (Â§9). **Implemented**; see "As built" at the end |
| R4 | Simple UI to pick folders to watch and sync | Folders screen: add (system folder picker), list, remove, per-folder counts (Â§6.2) |
| R5 | READ ONLY access to the folders | SAF tree grant requested with `FLAG_GRANT_READ_URI_PERMISSION` only. No storage permission in the manifest (Â§5) |
| R6 | Simple UI to select server and insert token | Server screen: address + token fields, "Test connection", paste or QR deep link (Â§6.3) |
| R7 | Simple UI showing how many are synced and whether the server or the app (phone) is offline | Status screen: counts, last sync, and one clear state line (Â§6.1, Â§7) |

## 2. The fafnirx contract (what the app must speak)

Source of truth: `replicator/proto/fafnirx.proto`, service `fafnirx.v1.Collector`, gRPC on port `7443` (plain HTTP/2 on a
LAN/VPN, or `https://` behind a TLS proxy or tunnel). The app uses 5 of the 8 calls; it never uses `MarkDeleted`,
`GetUpdateInfo` or `DownloadUpdate` (updates come from GitHub releases, not the master).

**Every call carries metadata**
- `authorization: Bearer <device token>` (token shown once in the web app: Devices > add device).
- `x-fafnirx-instance: <32 hex chars>`: a random id made on first run and kept in app storage. It stops two installs from
  acting as one device (`FAFNIRX_INSTANCE_LEASE_SECS`, 90 s). Reinstalling makes a new id; the user then has to press
  **Release** on the device page in the web app or wait 90 s. The app surfaces this case (Â§7).

**Calls**

| Call | When | Notes |
|---|---|---|
| `Identify` | Start of every sync cycle | Returns `master_id`. If it differs from the stored one, drop the uploaded-files table and start over (a new database or another master) |
| `Heartbeat` | Every 60 s while a cycle runs, and once at the start and end | `os = "android"`, `hostname` = device model, `client_version`, `phase` (`scanning`/`hashing`/`uploading`/`idle`), `current_file`, `files_done/total`, `bytes_done/total`, `speed_bps`, `errors` (drained), `disk_*` = 0. Doubles as the reachability probe |
| `Negotiate(Manifest)` | Per file, after hashing | Sends path, size, root hash, chunk hashes; gets `missing` chunk indexes and `already_current` |
| `UploadChunks` | Per file with missing chunks | Client stream of `Chunk{hash, data, root_hash, index}` for the missing indexes only |
| `Commit` | Per file, after the chunks are uploaded, **unless `already_current`** (then nothing is sent: the master already holds this version; the Windows client does the same). Also needed when `missing` is empty but `already_current` is false (content known from another file of the device) | `mtime` in unix **nanoseconds**, `mode = 0`, no `renamed_from` |

**Hashing (must match `crates/common` bit for bit)**
- Chunks are fixed 4 MiB (the last may be shorter). Chunk hash = `BLAKE3(chunk)`, 32 bytes.
- `root_hash = BLAKE3(chunk_hash_0 || ... || chunk_hash_n || size as u64 little endian)`. An empty file has no chunks and
  root `BLAKE3(0u64 LE)`.
- Verified by committed test vectors (Â§10).

**Paths.** `<folder display name>/<path relative to the picked folder>`, `/` separated, NFC normalized
(`java.text.Normalizer`), each part at most 255 bytes, no `.`, `..`, empty or control parts, at most 4096 bytes total
(same rules as `clean_remote_path`). Two picked folders with the same display name get `-2`, `-3` appended to the second;
the choice is made once and stored. A file that fails validation is skipped and reported through `errors`.

**Errors.** gRPC status comes in the HTTP/2 trailers. `UNAUTHENTICATED` (unknown or revoked token) -> stop and show
"re-enter token"; no retry until the token is changed (the Windows client just treats it as offline and retries).
`PERMISSION_DENIED` ("device in use: another computer ...") -> end the cycle, show "device in use by another install", try
again next cycle. `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `CANCELLED`, `UNKNOWN`, `RESOURCE_EXHAUSTED`, `INTERNAL`, and transport
errors -> server offline, end the cycle, backoff. Any other status on a file -> that file fails.
**Per-file failures** (unreadable, changed during upload, bad path, other statuses) are recorded with a count and retried
after 10 min, 1 h, 1 day, then not again until the file's size or mtime changes (same as the Windows client). The message
goes into the next `Heartbeat.errors`.

## 3. Native and lightweight: decisions

| Area | Choice | Why not the usual choice |
|---|---|---|
| Language | Kotlin, no coroutines library | Plain threads + a single worker executor are enough for one serial upload pipeline |
| UI | Framework `View`s (`Activity`, `LinearLayout`, `RecyclerView`-free simple lists via `ListView`) | Compose/AppCompat/Material add several MB and a runtime. Three small screens do not need them |
| Network + gRPC | **grpc-java, OkHttp transport** (`io.grpc:grpc-okhttp`): `OkHttpChannelBuilder.forAddress(host, port)` with `usePlaintext()` (h2c) for `http://` and TLS for `https://` | The standard, well-tested client: HTTP/2, trailers/status, deadlines, flow control, keepalive and TLS handled by the library. Costs ~2-3 MB, accepted for reliability |
| Protobuf | **protobuf-javalite** with Java stubs generated at build time from a copy of `fafnirx.proto` (Gradle protobuf plugin, `protoc` and `protoc-gen-grpc-java` fetched as Maven artifacts inside the Docker build) | Lite runtime is small and works with R8. The proto is copied to `proto/fafnirx.proto`; `proto/sync.sh` refreshes it from the server repo and a test fails if the rpc list is not the 8 known calls. Generated code is not committed |
| Streaming | `CollectorGrpc.newStub(channel).uploadChunks(responseObserver)` returns a request observer: `onNext(Chunk)` per missing chunk, `onCompleted()`, then the `UploadSummary` arrives. Wait for `isReady()` (flow control) before each `onNext` so only one 4 MiB chunk is in memory | Same streaming as the Windows client. Set `maxInboundMessageSize`/outbound as needed (chunk 4 MiB, server limit 8 MiB) |
| Auth metadata | One `ClientInterceptor` adds `authorization: Bearer <token>` and `x-fafnirx-instance` to every call | |
| BLAKE3 | Own Kotlin implementation of the reference algorithm (~200 lines, chunk + parent compression, no SIMD), tested with official vectors | No library worth a dependency. **Gate:** benchmark on a mid-range phone in phase 1. If under ~50 MB/s, stay in Kotlin: hash with the JIT-friendly tight loop (`IntArray` state, no allocation per block) and use several cores, since the chunks of a file are independent (the 4 MiB chunks are hashed in parallel, then the root). Upload is network-bound anyway |
| Storage | `SQLiteOpenHelper` (framework) for state, `SharedPreferences` for settings | Room needs kapt/ksp and a runtime |
| Background | `JobScheduler` (periodic, network + battery constraints) starts a foreground `Service` (type `dataSync`) while uploading | WorkManager pulls in AndroidX |
| Token storage | Android Keystore AES-GCM key wraps the token in `SharedPreferences` | Framework API, no library |
| Shrinking | R8 full mode, resource shrinking, `minSdk 26`, English only for v1 (strings in `strings.xml`), one vector icon | Keeps the APK small |
| Native libraries | None. Pure Kotlin/JVM bytecode, no NDK, no JNI, no C | `Dockerfile` does not install the NDK |

Size budget: APK < 4 MB (measure in phase 0 and set the CI limit from the real number; R8 keep rules for grpc/protobuf-lite go in `proguard-rules.pro`). CI prints the size and fails above the budget.

## 4. Architecture

Package `app.fafnirx.android`, single module `app/`.

```
ui/        MainActivity (3 views in one activity: Status, Folders, Server), FolderAdapter
sync/      Scanner, Hasher (Blake3, ChunkHasher), Uploader, SyncEngine, SyncService, SyncJobService, Status (state machine)
net/       Channel (ManagedChannel builder, interceptor), Collector (blocking/async wrappers, status mapping), NetworkState (ConnectivityManager)
proto/     fafnirx.proto (copy of the server's), generated stubs go to build/
data/      Db (SQLiteOpenHelper), Settings (SharedPreferences + Keystore), InstanceId
```

**SQLite tables**
- `folder(id, tree_uri, display_name, remote_prefix, added_at)`
- `file(folder_id, doc_id, rel_path, size, mtime_ms, root_hash, chunk_hashes BLOB, state, last_error, committed_at)`
  with `state` in `new | hashed | uploaded` and primary key `(folder_id, doc_id)`.
- `kv(key, value)` for `master_id`, last successful contact, counters.

A file is **synced** when `state = uploaded` and its `(size, mtime_ms)` still match what the scan sees. A changed file goes
back to `new` (the master keeps the older version; it is the master's rule, not ours).

**Sync cycle** (`SyncEngine.run`), serial, one file at a time:
1. Check network (`NetworkState`); apply the Wi-Fi-only/charging options (Â§6.3). If not allowed, set the state to
   "waiting" and stop.
2. `Identify`. Failure -> server offline (Â§7). New `master_id` -> clear `file`.
3. **Scan** every folder: walk with `DocumentsContract.buildChildDocumentsUriUsingTree` + one `ContentResolver.query` per
   directory for `document_id, display_name, mime_type, _size, last_modified` (the only fast way over SAF). Upsert rows.
   A file whose `last_modified` is less than 30 s old, or whose size changed since the previous scan, is skipped this
   cycle (still being written).
4. For each `new`/changed file, oldest first: hash (stream through `openInputStream`, 4 MiB buffer reused, record chunk
   hashes), store hashes, `Negotiate`; if `missing` is not empty, reopen with `openFileDescriptor("r")`, seek to each
   missing chunk and send it via `UploadChunks` (re-check `size` and `mtime` first; if changed, drop and rehash next
   cycle); `Commit`; mark `uploaded`.
5. `Heartbeat` every 60 s; final `Heartbeat` with `phase = idle`.
6. Never holds more than one 4 MiB chunk (plus the hash state) in memory. Partial uploads resume at chunk level through
   `Negotiate`, so a kill loses at most one chunk.

**Triggers and where the work runs.**
- *Periodic:* a `JobScheduler` job every 15 min (the minimum) with `NETWORK_TYPE_ANY` or `UNMETERED`, optionally
  `requiresCharging`. The work runs inside the job, which Android may stop after about 10 minutes; progress is kept in
  SQLite and uploads resume at chunk level, so the next run continues. This is enough for a day's photos.
- *Sync now / first big backup:* started from the visible UI, runs in a foreground `Service` (type `dataSync`) with a
  notification (progress, Stop). A foreground service cannot be started from the background on Android 12+, so the periodic
  job never starts one. Android 15 limits `dataSync` services to about 6 hours per 24 h; `onTimeout` stops cleanly and the
  periodic job continues. "Sync now" ignores the Wi-Fi-only and charging options after a confirmation ("use mobile data?").
- *Also:* app start and connectivity regained (while the UI is open). No `ContentObserver` (unreliable on SAF trees); the
  periodic scan is the change detector.
- Opening the Status screen runs one quick `Identify` so "Server offline" is current, not the result of the last job.

**Resume after reboot.** Persisted `JobScheduler` jobs survive reboots; that needs the normal `RECEIVE_BOOT_COMPLETED` permission (no prompt, no storage access).

## 5. Permissions and read-only guarantee

**Manifest permissions (complete list)**
- `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (persisted jobs)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (Android 13+, asked when the first sync starts)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **not** declared; the app only opens the system settings screen on request.

**No** `READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`,
`ACCESS_MEDIA_LOCATION`. Folder access comes only from the user's own choice in the system picker:

- Add folder = `Intent(ACTION_OPEN_DOCUMENT_TREE)` with `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
  and **no** `FLAG_GRANT_WRITE_URI_PERMISSION`. The result is persisted with `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.
- The OS enforces read-only: the app holds no write grant, so it cannot modify, create or delete anything there, whatever
  bugs it has. Code only uses `query` and `openInputStream`/`openFileDescriptor("r")`; there is no `delete`, `createDocument`,
  `renameDocument` or `"w"` mode anywhere (a unit test greps the source for them and fails the build).
- Removing a folder in the app calls `releasePersistableUriPermission`, so the grant goes away too.
- Original bytes are read, so EXIF (including GPS) is intact; SAF does not redact like MediaStore does.
- Limits of SAF the UI explains: since Android 11 the system picker refuses the storage root and `Download`; pick a
  subfolder (e.g. `DCIM/Camera`, `Pictures`, `Documents`). Any folder works, not only media.

## 6. UI

Single activity, plain views, light/dark from the system. Bottom row with three tabs: **Status**, **Folders**, **Server**.
First launch with no server set opens **Server**.

### 6.1 Status
```
  â— Synced                      <- one state line + colored dot (Â§7)
  Last sync: 14:32, 2 min ago

  Synced        3,280
  Waiting         120
  Failed            2   (tap: list with the error per file)

  Uploading IMG_2044.jpg  62%   4.1 MB/s     <- only while syncing
  [ Sync now ]                  [ Stop ]
```
Counts come from the `file` table (synced / new+hashed / error). Updated live through a listener on the engine
(`Handler` on the main looper), no polling.

### 6.2 Folders
List of picked folders: display name, path hint, `synced / total`, remove (confirm dialog; removing does not touch the
master). A `+ Add folder` button opens the system picker. Footnote: "Read only: fafnirx-android can never change or delete
your files."

### 6.3 Server
- **Server address** (`192.168.1.10:7443`, `http://nas.lan:7443`, `https://fafnirx.example.com`). No scheme means `http://`;
  no port means `7443` for http. A public address over `http://` shows a warning.
- **Token** (password field, paste button, "show").
- **Test connection**: `Identify` + `Heartbeat`, shows "Connected as *<device_name>*" or the specific error (unreachable,
  unauthorized, device in use). Save is allowed even if the test fails.
- Options: "Wi-Fi only" (default on), "Only while charging" (default off).
- Deep link `fafnirx://enroll?server=...&token=...` fills the form (for the QR code the server plan will add; the phone's
  camera app opens the link). Without that server feature the user pastes.
- Cleartext: allowed because the host is user-entered; implemented as `usesCleartextTraffic="true"`; the channel is only ever built for the configured host.

## 7. Status model (online/offline)

Evaluated in this order; the first match is the state line and dot color.

| State | Dot | Condition | Text |
|---|---|---|---|
| Not configured | grey | no server or token, or no folders | "Set up server" / "Add a folder" |
| **Phone offline** | red | `ConnectivityManager` has no validated network | "Phone is offline" |
| Waiting for network | amber | online but Wi-Fi-only is set and only mobile data is available (or charging required) | "Waiting for Wi-Fi" / "Waiting for charger" |
| **Server offline** | red | last `Identify`/`Heartbeat` failed with a transport error (connect, timeout, DNS, TLS) | "Server not reachable (last seen 14:10)" |
| Auth problem | red | `UNAUTHENTICATED` | "Token rejected: enter a new token" |
| Device in use | amber | instance-lease refusal | "Used by another install; press Release in the web app" |
| Syncing | blue | a cycle is uploading | "Syncing 120 of 3,400" |
| Synced | green | last cycle finished, nothing waiting | "Synced" |
| Errors | amber | cycle finished, some files failed | "Synced, 2 files failed" |

Phone offline vs server offline is decided by the system's connectivity state first; only if the phone has internet and the
server call fails is it "Server offline". The state also updates when connectivity changes
(`registerDefaultNetworkCallback`, registered only while the UI is visible or a cycle runs).

## 8. Build with Docker (no host tooling)

Host needs only Docker. No JDK, Android SDK or Gradle on the host.

- `docker/Dockerfile`: pinned base (`eclipse-temurin:17-jdk-jammy`), installs Android `cmdline-tools`, `platform-tools`-less
  SDK: `platforms;android-35`, `build-tools;35.0.0` (versions pinned, licenses accepted non-interactively), and Gradle via
  the project's `gradlew` wrapper (distribution pinned and checksum-verified in `gradle-wrapper.properties`).
- `build.sh` wraps the commands below; it contains no logic beyond docker calls:
  ```
  docker build -t fafnirx-android-build docker/
  docker run --rm -v "$PWD":/src -v fafnirx-gradle:/root/.gradle -w /src fafnirx-android-build ./gradlew --no-daemon test assembleRelease
  ```
  Output: `app/build/outputs/apk/release/fafnirx-android-<version>.apk`. Named volume `fafnirx-gradle` caches dependencies.
- `docker/sign.sh` (runs in the container): `keytool` generates `fafnirx.keystore` if `$KEYSTORE` is not provided, then
  `apksigner` signs and verifies (v2/v3 scheme). Locally the generated key is stored in `./.signing/` (git-ignored) so every
  local build uses the same key and updates install over each other.
- Targets in `build.sh`: `build` (APK), `test` (unit tests + size check), `shell` (a shell in the container), `clean`.
- Reproducibility: pinned base image digest, pinned SDK versions, pinned dependency versions (`gradle.lockfile`).

Project config: `minSdk 26`, `targetSdk 35`, `compileSdk 35`, `versionName` from a `VERSION` file, `versionCode` derived from
it. `applicationId = app.fafnirx.android`. Release variant: R8 full mode, shrinkResources, no debuggable.

## 9. GitHub workflow

`.github/workflows/build.yml`

- **Triggers:** push and pull request to `master`; tag `v*`; `workflow_dispatch`.
- **Job `build`** (`ubuntu-latest`):
  1. `actions/checkout`
  2. Restore signing key: if secret `ANDROID_KEYSTORE_BASE64` (+ `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`) exists, decode
     it to `.signing/`; otherwise the build generates a one-off self-signed key (the APK installs, but a later build will not
     update over it, and the log says so).
  3. `./build.sh test` then `./build.sh build` (the same Docker commands as local; Docker layer cache through
     `docker/build-push-action` with `type=gha`, Gradle volume through `actions/cache`).
  4. Print `apksigner verify --print-certs` and the APK size; fail if over the size budget.
  5. `actions/upload-artifact`: the APK and its `.sha256`.
- **Job `release`** (only on `v*` tags, needs `build`): creates a GitHub Release with the APK and checksum
  (`softprops/action-gh-release`, pinned by SHA), `contents: write` permission only on this job.
- One-time setup documented in the README: generate the key with `./build.sh keygen` and store it as the three secrets.
  "Self-signed" means exactly this: a key we make ourselves, not a Play/CA identity. Users sideload and keep the certificate
  fingerprint printed in the release notes.
- All third-party actions pinned to a full commit SHA; default `permissions: contents: read`.

## 10. Testing

- **Unit (JVM, run inside Docker):**
  - BLAKE3 against the official test vectors (input lengths 0, 1, 63, 64, 1023, 1024, 1025, 4 MiB-1, 4 MiB, 4 MiB+1, multi-chunk).
  - `ChunkHasher` root hashes against fixtures produced by `fafnirx-common` (a small Rust test in the server repo prints
    them; committed as `app/src/test/resources/vectors.json`). This is the guard against protocol drift.
  - Upload logic against an in-process gRPC server (`grpc-inprocess`, test scope) that fakes `Collector`: missing-chunk selection, `already_current` skip, status-to-state mapping, auth metadata present on every call.
  - Path rules (NFC, 255-byte parts, control chars, clash suffixes).
  - State machine for the status model (Â§7) driven by fake inputs.
  - Read-only guard: fails if the sources contain `createDocument`, `deleteDocument`, `renameDocument`,
    `FLAG_GRANT_WRITE_URI_PERMISSION`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` or an `"w"`/`"rw"` open mode.
- **End to end (manual first, scripted later):** emulator or device against a master started with the server repo's
  `docker-compose.yml` (`10.0.2.2:7443` from the emulator). Checks: files appear in the web app with the right path and
  mtime; second run uploads nothing; kill the app mid-upload and the committed hash on the master still matches; airplane
  mode shows "Phone offline", stopping the master shows "Server offline", rotating the token shows "Token rejected".
- **Device matrix:** one stock Android (Pixel) and one aggressive-battery OEM; a 2 GB video over Wi-Fi.

## 11. Phases

| # | Result | Done when |
|---|---|---|
| 0 | `Dockerfile`, `build.sh`, empty app, CI producing a self-signed APK | `./build.sh build` works with only Docker installed; the workflow uploads an installable APK |
| 1 | `Blake3`, `ChunkHasher`, generated stubs, `Channel`/`Collector`; Identify/Heartbeat/Negotiate/UploadChunks/Commit against a local master | Test vectors match `fafnirx-common`; one file uploads and shows in the web app. BLAKE3 speed benchmark decides whether parallel chunk hashing is needed |
| 2 | Server screen, Folders screen (SAF read-only), Scanner, SQLite state | Chosen folders upload in the foreground; a second run uploads nothing |
| 3 | Status screen + state model, `JobScheduler` + foreground service, Wi-Fi/charging options | Survives kill, airplane mode, reboot; shows the correct offline state in each case |
| 4 | Release workflow with tags, size budget check, README (install, signing key setup, SAF limits), battery-optimization hint | A tester installs the release APK and backs up `DCIM/Camera` |

## 12. Not in scope (v1)

- Viewing/downloading what the master holds (the contract has no such call; it needs the web API and a read-only API token).
- Rename detection, `MarkDeleted`, "free up space", two-way sync. A renamed or moved file looks new (its SAF document id
  changes): it costs no data (the master already has the content) but the old path stays on the master.
- Parity gaps with the Windows client, on purpose: no `.fafnirxignore`, no delete guard (it never deletes), no bandwidth
  limit, no pause (Stop only), one upload stream at a time instead of parallel streams, no self-update, no tray.
- Several servers per install, share-sheet upload, localization (strings are already in `strings.xml`), F-Droid and Play.
- Folder change notification (`ContentObserver`) and sub-15-minute periodic scans when the app is not running.

## 13. Risks and open questions

- **grpc-okhttp h2c against tonic:** `usePlaintext()` should work (tonic serves h2c); verify in phase 1. Fallback: require `https://`.
- **R8 and gRPC:** shrinking can break reflection-based parts (service config, name resolvers); keep rules and a release-build smoke test in phase 1.
- **SAF scan speed:** one query per directory; very large trees (100k files) may take minutes on the first scan. Mitigation:
  scan and upload in the same pass, per directory, and persist progress.
- **Kotlin BLAKE3 speed** on multi-GB videos: see the Â§3 gate.
- **Aggressive OEMs** kill background work: foreground service during uploads, visible "last sync", a link to the battery
  settings.
- **Server: offline alert (needed to avoid noise).** The master raises "Device offline" after 60 s without a heartbeat
  (`offline_after_secs`, a global setting). A phone that syncs for a few minutes every 15 min is "offline" most of the time and
  would raise this alert constantly, and the web app shows it offline. Needs a server change: skip the alert for devices
  with `os = "android"`, or a per-device threshold. Until then the setting can be raised globally (hides real outages of
  other devices).
- **SAF `last_modified`** can be missing on some providers: then the app cannot tell an edit from no change and rehashes the
  file every scan (costly); such files are only rehashed when `size` changes, and this is shown as a warning.
- **APK size** (< 4 MB with the gRPC lite stack and the Kotlin stdlib after R8) is an estimate; measure it in phase 0 and set the
  CI budget from the real number.
- **Server work wanted but not required:** a QR code with the `fafnirx://enroll` link on the device page; check that the web
  device list accepts `os = "android"`.
- Open: should a file the user edits (same name, new content) be re-uploaded as a new version automatically? Spec says yes (the
  master keeps 5 versions); confirm.

## 14. As built (v0.1.0)

Implemented and built in Docker. Differences from the text above:

- **Proto** lives in `app/src/main/proto/fafnirx.proto` (refresh with `scripts/sync-proto.sh`); Gradle is pinned in the
  Dockerfile (no wrapper). The `proto/` folder and `gradle.lockfile` of Â§8 are not used.
- **Strings** are in code, not `strings.xml`. Hidden names (starting with `.`) and virtual documents are skipped by the scanner.
- **GitHub workflow** uses `actions/*@v4` tags (not SHAs) and the `gh` CLI to publish the release (no third-party action).
- **Size:** the release APK is about 300 KB after R8 (the 4 MB budget in `docker/sign.sh` is a ceiling, not an estimate).
- **Verified:** 34 tests in Docker, including the reference BLAKE3 vectors, root hashes, the upload against a fake master in
  process and over TCP (plain HTTP/2), and the read-only guard; an end-to-end upload (empty, small and 8 MB files, then a
  repeat that sends nothing) to a real fafnirx master, with the stored files byte-identical and mtime kept.
- **Parallel uploads (added after the first build):** one thread reads and hashes the next files while 1 to 3 workers (setting on the Server screen, default 2) upload, all over one connection (sync/Pipeline.kt). Verified by 8 pipeline tests and by uploading 24 files (4 of them multi-chunk) in parallel to a real master, all byte-identical.
- **Not verified:** the app has not been run on a phone or emulator (none was available): the UI, the folder picker, the
  background job and foreground service, notifications, and the R8 release build at runtime. The keep rules for gRPC are in
  `app/proguard-rules.pro`; try the release APK first.
- **Still open:** the server raises "Device offline" after 60 s without a heartbeat (Â§13).

### 14.1 Version 1.1.0

- **Sync switch** on the Server screen: off cancels the periodic job, refuses Sync now, stops a running sync, and Status says
  "Sync is off" (§7 gets a row after "Not configured").
- **Review fixes:** upload deadline grows with the number of chunks (a fixed 15 minutes would time out multi-GB videos); the
  database uses WAL so the screens do not wait for a folder scan; UI refreshes are coalesced (one per 250 ms); a second Sync now
  no longer ends the first one's service; "phone offline" no longer requires a validated internet connection (a master on a
  Wi-Fi without internet is reachable); the token is decrypted once and cached.
- **Known limit:** JobScheduler may run the periodic job only on networks Android has validated for internet; on a LAN-only
  Wi-Fi use Sync now.
