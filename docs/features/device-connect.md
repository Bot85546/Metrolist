# Metrolitt Device Connect — implementation plan

Status: **design proposal; no application code implemented yet**  
Development branch: `feature/device-connect` (branched from `metrolitt`)

## Product goal

Treat the Galaxy S24 and Galaxy Tab S11 Ultra as two control surfaces for **one active Metrolitt playback session**. If the phone is playing music through Galaxy Buds, opening Metrolitt on the tablet should discover the already-paired phone, show the same song/playlist/queue/position, and let the tablet control playback. The sound must **continue on the phone to its existing output**. The reverse direction must work identically.

This is **remote control and state synchronization, not audio streaming**. We do not send the audio data or duplicate playback on both devices.

## User experience

1. **First time:** Open Device Connect on both devices, confirm a one-time secure pairing code, and trust the pair.
2. **Ordinary use:** Start music on either device. When Metrolitt is opened on the other, automatically reconnect to the active device, without a new prompt.
3. The controlling device displays accurate track/artist/artwork, active playlist context, current and upcoming queue, play/pause, seeking, next/previous, shuffle/repeat, and queue edits. Commands operate on the actual playback owner.
4. **Disconnect** terminates the remote-control connection without interrupting the owner's playback. Suppress automatic reconnection for that app foreground session; **Reconnect** is available immediately. **Forget device** removes pairing.
5. If both devices were independently playing before they see each other, do **not** silently stop either or choose a winner. Show an explicit choice of which session to control. Consider a separate **Play on this device** handoff later.
6. If the connection drops, show a disconnected state, keep the owner's audio playing, and retry briefly while the controlling app is in use. Never silently start local audio during a drop.

## Architecture proposal

### A. Transport and trusted pairing

- Start with **Google Nearby Connections** (Bluetooth/BLE/Wi-Fi, encrypted local peer-to-peer, no internet/server). This fits the two Galaxy devices, but requires Google Play services and appropriate runtime permissions.
- Put transport implementations in the `gms` source set as necessary; keep reusable models and playback interfaces in `app/src/main/kotlin/com/metrolist/music/connect/`. Do not break `foss`/`izzy` builds. **Verify which flavor the user's signed Metrolitt APK uses before integration** and keep the update package name `com.metrolitt.music` and signing compatibility.
- First pairing requires user-visible mutual verification. Subsequent links verify a persisted device identity and a shared proof stored using Android Keystore-backed protection; do not trust a broadcast display name alone.
- The active owner advertises only while an active playback session makes connecting useful; the other device discovers briefly when Metrolitt opens. Stop discovery immediately after linking; bound retry/backoff. Avoid idle scanning and unnecessary wake locks.

### B. Single authoritative player

- The **owner** is the only device running audible playback. Its existing `MusicService`, ExoPlayer, cache, audio focus, normalization, Galaxy Buds output, and hardware media-button handling continue untouched.
- The **controller** never opens a matching local stream. Its UI routes actions through a new playback-target/controller abstraction to the owner.
- Treat this as distinct from the existing `listentogether` feature, whose protocol/manager handle synchronized multi-listener playback. Reuse safe serialization/queue ideas where useful, but do not invoke Listen Together guest playback or drift-correction logic.

### C. State snapshots and commands

Owner-to-controller snapshot:
- Playback owner identity and connection status
- Track ID, title, artist, album, duration, artwork URL / small metadata reference
- Is-playing / buffering, timestamped playback position, speed
- Playlist/album/radio context, current queue index, upcoming queue, shuffle/repeat, available commands
- Monotonically increasing revision / sequence number

Controller-to-owner commands:
- Play, pause, next, previous, seek, select track
- Queue insert, remove, reorder, clear, change playlist
- Shuffle, repeat; optional **in-app remote volume** after feasibility testing

Commands carry unique IDs and acknowledgements; owner serializes/validates operations and sends confirmed state. On reconnection, discard stale commands and request a full snapshot. Page large queues on demand, rather than repeatedly transferring thousands of tracks. Locally advance the displayed progress bar using the timestamp; send updates on events and sparse correction snapshots, not high-frequency polling.

### D. Android / Samsung integration

- The app already uses a Media3 `MediaLibraryService` and `MediaLibrarySession` in `MusicService`; `PlayerConnection` currently exposes a concrete `ExoPlayer`. Plan the remote-state adapter around those existing interfaces carefully rather than replacing the playback engine.
- Expose a **non-audible remote session / Player adapter** on the controlling device so Android System UI media controls can display synchronized metadata and dispatch commands to the owner.
- **Samsung Now Bar is a device-testing milestone, not a guaranteed platform API.** Verify that remote MediaSession metadata/commands appear on the actual S24 and Tab S11 Ultra, without claiming privileged system-level remote-output integration.
- Hardware volume buttons on the controlling device normally affect its local volume. Test separately; do not promise that they control the other device. In-app remote volume control can be considered.
- Ensure that the remote controller creates no fake local playback, audio focus, duplicate notification, or persistent foreground work after disconnect.

## Proposed sequence

1. **Feasibility spike:** Check APK flavor, required permissions, Nearby connect/reconnect behavior on both Galaxy devices, and remote media session / Now Bar display. Record observed timing and limitations.
2. **Connection foundation:** Pairing, cryptographic trust, discovery, automatic reconnect, state machine, disconnect/forget controls, transport tests.
3. **Playback bridge:** Authoritative snapshots, command channel, event-driven progress, local/remote playback-target routing; pause/play/seek/next/previous end-to-end.
4. **Complete synchronization:** Playlist context, lazy queue paging/editing/reordering, shuffle/repeat, artwork, errors, play-on-controller selection.
5. **Polish:** Samsung media controls/Now Bar, role reversal, interruptions and Bluetooth disconnections, Wi-Fi-off behavior, performance tuning and regression tests.
6. **Release gate:** Build only when requested; install and test signed in-place updates on both devices; merge branch into `metrolitt` after acceptance.

## Acceptance criteria

- Phone-to-tablet and tablet-to-phone both work.
- One-time pairing; automatic reconnection after the controlling app opens, without repeated approval.
- Remote controls update the authoritative player quickly; no duplicate audio; the original Buds audio route stays active.
- Controller shows accurate track, playlist, queue and progress even after reconnect or queue edits.
- Manual disconnect does not stop playback or immediately auto-reconnect.
- No continuous background discovery or progress polling on an idle controller. Measure foreground/background battery and connection time on both real devices.
- Preserve Metrolitt's application ID, updates, playback performance, normalization and existing local media controls.
- Samsung Now Bar support is explicitly tested and its limitations documented before calling this feature finished.

## References

- [Google Nearby Connections overview](https://developers.google.com/nearby/connections/overview)
- [Nearby Connections Android permissions](https://developers.google.com/nearby/connections/android/get-started)
- [Android Media3 MediaSession and remote controls](https://developer.android.com/media/media3/session/control-playback)
- [Android media control surfaces](https://developer.android.com/media/implement/surfaces/mobile)
- [Samsung Now Bar support](https://www.samsung.com/uk/support/mobile-devices/how-to-use-the-now-bar-on-the-lock-screen-of-your-samsung-galaxy-device/)
