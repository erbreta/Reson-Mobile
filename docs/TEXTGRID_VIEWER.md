# Read-only TextGrid Viewer

Open **TextGrid Viewer** in the studio without recording audio, or **Open TextGrid**
in the analyser. Android's document picker grants access to the selected document;
no broad storage permission is requested. The app opens only a read stream, parses
in a background coroutine and retains the document in an activity ViewModel over
rotation. A failed import displays the reason and retains the previous document.
New imports cancel obsolete parsing. Documents must be reopened after process
death; this milestone does not persist annotation sessions or source URIs.

## Compatibility

- Canonical Praat long text and short text, including `ooTextFile short` headers.
- Multiple `IntervalTier` and `TextTier` tiers, including empty point tiers.
- UTF-8 with/without BOM, and BOM-marked UTF-16 LE/BE, decoded strictly. Binary,
  legacy byte encodings and BOM-less UTF-16 are rejected with an error.
- Unicode IPA/Arabic, combining marks, empty and multiline labels, doubled-quote
  escaping and `!` comments outside quoted strings. Original label strings and
  newline characters are preserved; no normalization or implicit replacement.
- Timestamp literals and decimal values are retained. The inspector shows original
  start/end literals and decimal interval duration; drawing/audio use double seconds
  without rounding to a fixed number of decimal places or rescaling.
- Finite, positive document/tier spans; tier bounds within the document; ordered
  annotations; positive intervals; strict point ordering without duplicates.
  Interval partitions use the desktop's 1 µs endpoint/gap tolerance without moving
  boundaries. Interval starts/ends must also increase, including at tiny durations.
  A document whose bounds cannot form a positive finite double timeline is rejected.
- Limits: 16 MiB input, 256 tiers, 100,000 total annotations, 65,536 UTF-16 code units
  per quoted string and 128 characters per timestamp literal. These are viewer
  resource limits, not limitations of Praat. Standard named fields and record indexes
  are validated in canonical order; arbitrary reordered/decorative long syntax is
  not supported. Unsupported syntax is rejected rather than guessed or repaired.

There is no TextGrid serializer, source overwrite, export, editing, tier creation,
boundary dragging, split/merge or undo/redo path in the Android implementation.
The desktop parser/serializer is unchanged.

## Audio and navigation

**Associate WAV / Change WAV** chooses from the existing saved Reson recordings.
It does not add a general-purpose external WAV importer or change recording storage.
Association is explicit: filenames are not used to guess an offset or rescale time.
The analyser and playback service are reused. Its ordinary file-format/rate and
memory limits continue to apply. Returning from standalone viewing preserves the
existing analyser's viewport when it is the same recording.

With audio, tiers appear immediately below the waveform/spectrogram canvas at the
same width and horizontal origin. They receive the same `TimeViewport`; dragging
or pinching a tier updates the existing analyser viewport. Tapping an interval sets
the existing audio selection; tapping a point positions its cursor. **Play Interval**
uses the existing focus-aware WAV player; **Zoom to Interval** uses at least the
existing 5 ms minimum viewport. The former 100 µs selection threshold was removed
so short imported intervals remain selectable.

Playback is an audition, not sample-accurate extraction. The player seeks in integer
milliseconds and checks interval ends every 20 ms, with additional device output
buffering. Intervals below 1 ms are inspectable/selectable/zoomable but their viewer
Play action is disabled. Their exact source timestamps are retained.

TextGrid domain bounds differing from `[0, WAV duration]` by more than 10 ms produce
a visible warning. No offset or scaling is applied. Out-of-WAV annotations remain
available in **TextGrid only** and Browse; playback/zoom actions are disabled where
an annotation exceeds audio bounds. Partial intervals are not silently clipped into
different selections.

Without audio, the viewer uses the document's original domain, including nonzero
or negative origins, with the same time-coordinate transform and zoom/pan controls.

## Compact tiers and labels

Rows stack in a bounded 240 dp scrolling panel, with names and Browse actions.
**Tiers** shows each tier's type/count and visibility switch; **Collapse tiers**
hides the rows. Names need not be unique: indexes identify tiers. The viewer draws
only annotations intersecting the viewport, using binary search over ordered
timestamps. Dense boundary/point drawing is limited to pixel density; labels are
drawn only where space permits, without changing time widths or overlapping point
labels. The canvas previews at most 256 code units with an ellipsis; the inspector
retains the complete label and its newlines. **Browse** lists every annotation with
normal touch targets, including tiny intervals, empty labels and closely spaced
points that cannot be separately tapped at the current zoom.

Compose text layout handles label direction independently of the left-to-right time
axis. It reuses IPA Studio's bundled Noto Sans plus Android font fallback, and the
existing Reson light/dark Material palettes. Shaping/fallback still needs device QA.

## Verification — 2026-10-31

The checked-in JVM fixtures cover long/short Praat text, UTF-8 BOM, both UTF-16
BOM orders, and native Praat output. Their expected TSV references preserve Unicode,
multiline labels, timestamps, names, and tier types. The fixtures were produced from
Reson Desktop and Praat before extraction; this standalone project contains the
fixtures and expected values, so build and test do not need either codebase.

Fixture attributes disable newline conversion for `.TextGrid` files. Tests cover all
seven fixture comparisons, unchanged input bytes, exact decimal literals, malformed
input/encoding, unsupported tiers, duplicate points, interval ordering, cancellation,
binary-search hit/visible ranges, shared-coordinate zoom, audio bounds and 10,000
intervals.

Device picker behavior, shaping, gestures, rotation, playback endpoint timing and
large-file phone responsiveness need device QA. Unit tests do not establish rendering
or hardware behavior.

Run the fixture and parser tests from this project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m' '-Pkotlin.compiler.execution.strategy=in-process'
```

Original imported TextGrids and WAVs are untouched.
