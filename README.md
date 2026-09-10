# Starfield

This app is inspired by the old school Windows screensaver by the same name. It started as a processing app that produced a looping GIF. It was then ported to Android. Then I removed the processing dependency.

It consists in a perspective projection of hundreds of thousands of stars streaming past the camera. The user can adjust the speed and star density using a simple XY touch interface.

## Features

- **Real perspective, not a 2D fake.** Every star is a quad in 3D world space pushed through a
  perspective and look-at matrix, so stars genuinely accelerate, spread, and sweep past the camera
  as they approach.
- **Continuous scroll.** The field is drawn twice from two depth-offset copies of the same vertex
  buffer, so there is never a visible seam or a moment where the screen empties.
- **Up to ~250,000 stars** in a single static vertex buffer, drawn in one call per slab.
- **Frame-rate independent motion.** Movement is integrated from a monotonic clock delta, so speed is
  identical at 60 Hz and 120 Hz.
- **Immersive fullscreen** with a system-bar-free presentation.
- **Multisampled** where the device supports it, gracefully falling back to no MSAA.
- **Debug-only FPS overlay** that never ships in a release build.

## Requirements

| | |
|---|---|
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 37 |

Point Gradle at your SDK with `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME` environment
variable. Building from Android Studio requires nothing further.

## Build and run

```bash
# Debug build + install onto a connected device or emulator
./gradlew installDebug

# Just build the APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Optimized build (R8 + resource shrinking)
./gradlew assembleRelease
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Controls

| Gesture | Effect |
|---|---|
| Drag / tap — **X** position | Flight speed, from a slow drift (left edge) to maximum warp (right edge) |
| Drag / tap — **Y** position | Star density, from almost nothing (top) to the full field (bottom) |

Both axes are mapped through an exponential ease-in curve rather than linearly, which gives fine
control over slow speeds across most of the screen while still reaching maximum warp at the edge.

## How it works

### Projection

`StarfieldRenderer` builds the same matrices a traditional GL pipeline would:

```kotlin
Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 500f, 0f, 0f, 0f, 0f, 1f, 0f)
Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 1f, MAX_Z)
```

A 60° vertical field of view with the camera parked 500 units back, looking down −Z at the origin.
Stars live in a slab stretching to `MAX_Z`, so the perspective divide naturally produces the
funnel — distant stars crowd the vanishing point, near ones fly wide past the edges.

### Geometry

Stars are generated once, at surface creation or resize, into a single `FloatBuffer`. Each star is an
axis-aligned quad of `STAR_SIZE` world units, built from two triangles (6 vertices, 18 floats), for
roughly **17 MiB** of vertex data at the maximum star count. Random positions are rejected from a
small rectangle at the centre of the field so stars never pile up on the vanishing point.

No texture, no per-star size or colour: the fragment shader writes solid white. All of the visual
variety comes from perspective scaling the quads.

### The endless scroll

The trick that keeps the field infinite is that it is drawn **twice** from the same buffer at two
depths one `MAX_Z` apart:

```kotlin
zOffset1 += speed * elapsedSeconds
zOffset2 += speed * elapsedSeconds
if (zOffset1 >= MAX_Z) zOffset1 -= 2 * MAX_Z
if (zOffset2 >= MAX_Z) zOffset2 -= 2 * MAX_Z

drawStars(zOffset1)
drawStars(zOffset2)
```

While one copy wraps around, the other is already covering the gap, so there is no seam. Because the
offset is integrated from a clock delta, motion is identical regardless of frame rate.

### Density

Density control is just a count. `starPercentage` scales how much of the buffer is submitted, and
since the buffer was generated with a random distribution, drawing any prefix of it yields a uniform
random subset:

```kotlin
val starsToDraw = (maxStarCount * starPercentage).roundToInt()
GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, starsToDraw * 6)
```

### Antialiasing

`MainActivity` installs a custom `EGLConfigChooser` that walks down a list of sample counts —
16, 8, 4, 2 — returning the first configuration the driver accepts, and falling back to a
non-multisampled config if none are available.

## Tuning

The interesting numbers live at the top of two files.

| Constant | File | Default | Meaning |
|---|---|---|---|
| `ORIGINAL_STAR_COUNT` | `MainActivity.kt` | `150000 / 4` (37,500) | Stars drawn at launch |
| `INITIAL_SPEED` | `MainActivity.kt` | `3000f` | Starting forward speed |
| `STAR_SIZE` | `StarfieldRenderer.kt` | `7.5f` | Side length of a star quad, world units |
| `MAX_Z` | `StarfieldRenderer.kt` | `30000f` | Depth of the star field |
| `VIEWPORT_RANGE` | `StarfieldRenderer.kt` | `3.5f` | Field half-width, in viewport widths |
| `DEAD_CENTER_RANGE` | `StarfieldRenderer.kt` | `0.075f` | Half-extent of the empty rectangle at the vanishing point, as a fraction of the viewport |

`maxStarCount` is derived from `ORIGINAL_STAR_COUNT` by inverting the density curve, giving a ceiling
of **249,632** stars (≈3.0 M vertices per frame at full density). At launch, 37,500 are drawn.

The field is regenerated whenever the surface changes, so rotating the device produces a fresh random
layout — as does every cold start, since the generator is unseeded.

## Debug builds

Debug builds show a two-line overlay in the top-left corner:

```
60 FPS
37500 stars
```

The frame rate is a true presented-frame average: `GLSurfaceView` offers no frame callback, so a
small decorator wraps the renderer, counts `onDrawFrame` calls on the GL thread, and reports once per
second alongside the number of stars actually submitted for that frame. Each measurement window starts
at its own first frame, so time spent paused or backgrounded never drags the average down.

The whole overlay — the view, the counting, the second line — is gated behind `BuildConfig.DEBUG`.
Release builds receive the bare renderer, so the counter costs nothing there. This is why the
`buildConfig` feature is enabled in `app/build.gradle.kts`: without it, `BuildConfig.DEBUG` does not
exist to check.

## Icon generation

The launcher icon is generated rather than drawn by hand. `tools/generate_icon.py` is a dependency-free
Python script that reuses the renderer's own perspective and look-at matrices to project squares into
2D, then emits an SVG where every square sits fully inside the circle inscribed in the canvas:

```bash
python tools/generate_icon.py [output.svg]
```

Output lands in `icons/`, which is git-ignored — check in only what you actually ship.

## Project layout

```
app/src/main/java/ca/justinmo/starfield/
├── MainActivity.kt        Activity, immersive mode, EGL config, touch → speed/density
├── StarfieldRenderer.kt   Buffer generation, matrices, the two-slab draw loop
├── StarShaders.kt         Inline GLSL ES vertex and fragment shaders
└── Interpolation.kt       Exponential ease-in curve used by the touch mapping

app/src/main/res/
├── layout/main_layout.xml GLSurfaceView plus the debug FPS overlay
└── mipmap-anydpi-v26/     Adaptive launcher icon

tools/generate_icon.py     Offline icon generator
```

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 Justin Michaud-Ouellette