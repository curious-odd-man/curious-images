# Video support (Phase 2) — dependency to add

The uploaded project archive contained only `src/main` (no `pom.xml`/`build.gradle`), so this
couldn't be added directly. Add the following before building:

## Maven (`pom.xml`)

```xml
<dependency>
    <groupId>org.bytedeco</groupId>
    <artifactId>javacv-platform</artifactId>
    <version>1.5.10</version>
</dependency>
```

## Gradle (`build.gradle` / `build.gradle.kts`)

```
implementation("org.bytedeco:javacv-platform:1.5.10")
```

## Why `javacv-platform` specifically

This resolves the implementation plan's open item *"JavaCV/FFmpeg wrapper choice — pick a
specific library at implementation time (licensing/size tradeoffs vary)"*.

- `javacv-platform` bundles native FFmpeg binaries for all major OS/architecture combinations, so
  no separate FFmpeg install is required on end-user machines — consistent with the plan's
  "bundled/downloaded like the ONNX models" framing for the video-decode dependency.
- It's LGPL/GPL depending on which FFmpeg components get linked; the platform artifact defaults to
  the LGPL build, which is fine for a desktop app that doesn't statically link. If distribution
  size matters more than convenience, `org.bytedeco:ffmpeg-platform` (the lower-level artifact
  javacv-platform depends on) plus hand-written JNI calls is the leaner alternative, but
  `javacv-platform` is used directly by:
  - `VideoMetadataExtractor` (`domain/imports/metadata/VideoMetadataExtractor.java`) — probes
    duration, codec, frame rate, rotation, creation-date, and GPS via `FFmpegFrameGrabber`.
  - `VideoThumbnailGenerator` (`domain/common/thumbnail/VideoThumbnailGenerator.java`) — seeks to
    a representative frame and decodes it via `FFmpegFrameGrabber` + `Java2DFrameConverter`.

## Native library size note

`javacv-platform` pulls in natives for Windows/Linux/macOS × x86_64/arm64 by default, which is
large. Once a target platform is settled, narrow this with the `javacv-platform` classifier
mechanism (e.g. only `linux-x86_64,windows-x86_64,macosx-arm64`) to avoid shipping natives nobody
needs.
