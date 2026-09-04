# Ebb & Fold / Flux & Fold

Android live wallpapers for the Pixel Fold family (built and tuned on a Pixel 11 Pro Fold) that turn the hinge into a scrub wheel: a 120-frame animation is mapped onto the fold angle, so opening and closing the device physically scrubs the artwork back and forth. Unfolding plays the sequence once; refolding scrubs it back under your hand in real time; the closed cover screen rests on the final frame.

## Download

Grab an APK from the [latest release](https://github.com/peakmotiondevelopment/hinge-scrub-wallpaper-Pixel_11_Fold/releases/latest) and sideload it. Each is a separate app, so you can install any or all three and pick between them in the wallpaper picker.

| Wallpaper | Artwork | Size | APK |
|---|---|---|---|
| **Ebb & Fold** | Liquid gold | 29 MB | [Ebb-and-Fold-v1.0.apk](https://github.com/peakmotiondevelopment/hinge-scrub-wallpaper-Pixel_11_Fold/releases/download/v1.0/Ebb-and-Fold-v1.0.apk) |
| **Flux & Fold** | Warm-to-cool liquid glass streak | 26 MB | [Flux-and-Fold-v1.0.apk](https://github.com/peakmotiondevelopment/hinge-scrub-wallpaper-Pixel_11_Fold/releases/download/v1.0/Flux-and-Fold-v1.0.apk) |
| **Flux & Fold 2** | Liquid glass streak, re-rendered | 13 MB | [Flux-and-Fold-2-v1.0.apk](https://github.com/peakmotiondevelopment/hinge-scrub-wallpaper-Pixel_11_Fold/releases/download/v1.0/Flux-and-Fold-2-v1.0.apk) |

Requires Android 11 (API 30) or newer. The hinge effect needs a device that reports `TYPE_HINGE_ANGLE`; on any other phone the wallpaper still installs and shows the final frame as a static image.

### Install

Either open the downloaded APK on the phone and allow installation from your browser or file manager, or sideload over USB:

```sh
adb install -r Ebb-and-Fold-v1.0.apk
```

Then choose it on the device: **Wallpaper & style → More wallpapers → Live wallpapers → Ebb & Fold** (or Flux & Fold / Flux & Fold 2). The picker preview auto-scrubs the animation on a loop so you can see the effect before applying it.

### Verify a download

Release APKs are signed with a self-signed release key. `SHA256SUMS.txt` is attached to the release; check a download against it with `shasum -a 256 -c SHA256SUMS.txt`. The signing certificate fingerprint is:

```
SHA-256: 22:3C:46:CD:52:33:C6:14:09:CD:F3:99:F4:9B:30:92:BC:BE:A7:48:53:12:87:3B:0C:BE:A8:A1:56:C2:59:B5
```

## Build from source

### Prerequisites

- JDK 17 or newer
- Android SDK with platform 35 installed (`ANDROID_HOME` set), **or** a current Android Studio, which bundles both
- A device or emulator is *not* needed to build — only to run

### Build

```sh
./gradlew assembleEbbfoldDebug     # Ebb & Fold
./gradlew assembleFluxfoldDebug    # Flux & Fold
./gradlew assembleFluxfold2Debug   # Flux & Fold 2
```

Each flavor is a separately installable wallpaper with its own frame set under `app/src/<flavor>/assets/frames/`. APKs land at `app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`.

To produce signed release builds, create a `keystore.properties` in the project root pointing at your own keystore:

```properties
storeFile=keystore/release.p12
storePassword=…
keyAlias=…
keyPassword=…
```

Then run `./gradlew assemble<Flavor>Release`. Without that file, release builds are simply left unsigned and debug builds are unaffected.

Run the tests with `./gradlew testEbbfoldDebugUnitTest` (79 pure-JVM unit tests covering the angle mapping, the animator, and the frame cache).

(`./gradlew generateFrames` renders a procedural placeholder set into `app/src/main/assets/frames/` for development only; it is not needed for the shipped flavors.)

## Swap in custom frames

The animation is just a numbered image sequence. To use your own (for example, frames rendered from After Effects):

1. Replace the images in `app/src/<flavor>/assets/frames/` (JPEG or PNG — JPEG preferred for photographic frames; `.jpg` is tried first).
2. Keep the exact naming: `frame_000.jpg` … `frame_119.jpg` (or `.png`).
3. Keep the exact count (120 frames) and size (1040×1080).
4. Rebuild and reinstall.

`frame_000` is shown fully closed (0°) and `frame_119` fully open (180°), so author the sequence with the final frame as the "settled" resting image. A video can also be the source — extract and conform with ffmpeg (center-crop to 1040×1080, resample to 120 frames).

Adding a whole new wallpaper means adding a flavor: a frame set at `app/src/<flavor>/assets/frames/`, a `res/values/strings.xml` with `app_name`, a 512 px `res/drawable-nodpi/wallpaper_thumbnail.png` for the picker, and one `create("<flavor>")` block in `app/build.gradle.kts`.

## How it works

The wallpaper engine listens to the `TYPE_HINGE_ANGLE` sensor while visible. Raw angles map linearly from the visible range 45°–180° onto frames 0–119 (below 45° the inner screen is off, so the sequence rests at frame 0 while closed). Unfolding plays the full sequence once at an eased, rate-capped pace; refolding scrubs it under the hinge; the closed cover pins the final frame.

Playback runs one step per display vsync (Choreographer) and is decode-gated: the displayed frame only ever advances onto frames that are already decoded, so decode latency can stretch the sweep by a vsync but never causes a skipped or wrong frame. Frames are JPEG-decoded (RGB_565) by three background threads into a 25-frame ring cache biased 18 frames ahead of the direction of travel, with evicted bitmaps recycled through an `inBitmap` pool. An engine repaints only when the frame on its surface would change. The picker preview ignores the sensor and auto-scrubs 45°→180°→45° on a ~4 s loop. On devices without a hinge sensor (or on the cover display), the wallpaper shows the static final frame.
