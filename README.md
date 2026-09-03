# Ebb & Fold / Flux & Fold

## What it is

Two Android live wallpapers for the Pixel Fold family (built and tuned on a Pixel 11 Pro Fold) that turn the hinge into a scrub wheel: a 120-frame animation is mapped linearly onto the fold angle, so opening and closing the device physically scrubs the artwork back and forth. Fully open (180°) rests on the calm final frame; folding toward closed plays the animation in reverse, smoothly and in real time.

## Prerequisites

- JDK 17 or newer
- Android SDK with platform 35 installed (`ANDROID_HOME` set), **or** a current Android Studio, which bundles both
- A device or emulator is *not* needed to build — only to run

## Build

From the project root:

```sh
./gradlew assembleEbbfoldDebug    # Ebb & Fold  (liquid gold)
./gradlew assembleFluxfoldDebug   # Flux & Fold (warm-to-cool liquid glass)
```

Each flavor is a separately installable wallpaper with its own frame set under `app/src/<flavor>/assets/frames/`. APKs land at:

```
app/build/outputs/apk/ebbfold/debug/app-ebbfold-debug.apk
app/build/outputs/apk/fluxfold/debug/app-fluxfold-debug.apk
```

(`./gradlew generateFrames` renders a procedural placeholder set into `app/src/main/assets/frames/` for development only; it is not needed for the shipped flavors.)

## Sideload

With developer options and USB debugging enabled on the phone and the device connected over USB:

```sh
adb install -r app/build/outputs/apk/ebbfold/debug/app-ebbfold-debug.apk
```

## Select the wallpaper

On the device: **Wallpaper & style → More wallpapers → Live wallpapers → Ebb & Fold** (or Flux & Fold). The picker preview auto-scrubs the animation on a loop so you can see the effect before applying it.

## Swap in custom frames

The animation is just a numbered PNG sequence. To use your own (for example, frames rendered from After Effects):

1. Replace the images in `app/src/<flavor>/assets/frames/` (JPEG or PNG — JPEG preferred for photographic frames; `.jpg` is tried first).
2. Keep the exact naming: `frame_000.jpg` … `frame_119.jpg` (or `.png`).
3. Keep the exact count (120 frames) and size (1040×1080).
4. Rebuild and reinstall: `./gradlew assemble<Flavor>Debug`, then `adb install -r` the new APK.

`frame_000` is shown fully closed (0°) and `frame_119` fully open (180°), so author the sequence with the final frame as the "settled" resting image. A video can also be the source — extract and conform with ffmpeg (center-crop to 1040×1080, resample to 120 frames).

## How it works

The wallpaper engine listens to the `TYPE_HINGE_ANGLE` sensor while visible. Raw angles map linearly from the visible range 45°–180° onto frames 0–119 (below 45° the inner screen is off, so the sequence rests at frame 0 while closed). Unfolding plays the full sequence once at an eased, rate-capped pace; refolding scrubs it under the hinge; the closed cover pins the final frame.

Playback runs one step per display vsync (Choreographer) and is decode-gated: the displayed frame only ever advances onto frames that are already decoded, so decode latency can stretch the sweep by a vsync but never causes a skipped or wrong frame. Frames are JPEG-decoded (RGB_565) by three background threads into a 25-frame ring cache biased 18 frames ahead of the direction of travel, with evicted bitmaps recycled through an `inBitmap` pool. An engine repaints only when the frame on its surface would change. The picker preview ignores the sensor and auto-scrubs 45°→180°→45° on a ~4 s loop. On devices without a hinge sensor (or on the cover display), the wallpaper shows the static final frame.
