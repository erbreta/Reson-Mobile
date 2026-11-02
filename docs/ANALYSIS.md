# Offline acoustic analyser

The analyser uses canonical mono PCM16 WAVs. Capture uses 44.1/48 kHz; imported
PCM16 mono/stereo WAVs at 16–96 kHz preserve their source rate and timing, with
stereo channels averaged into the analysis copy. The comparison corpus and results
are unchanged. Imported-WAV coverage adds a 16 kHz known-tone regression; it makes
no new claim about Praat accuracy.

## Using the screen

Select a saved recording, then **Analyse**. Waveform is always visible. The
spectrogram starts enabled; other layers start disabled. **Layers** opens the
toggles and pitch/formant settings; preferences persist locally. **Navigate**
supports horizontal drag and pinch zoom. **Select** supports dragging an interval
and either endpoint; pinch still zooms. Tap for a cursor. Sliders provide an
alternative way to adjust cursor/range, and buttons provide zoom/fit controls.
**Measurements** opens cursor values or interval summaries. Playback uses the
existing audio-focus-aware player, seeks to the interval start, and checks its
end every 20 ms. This is audition playback, not sample-accurate interval export;
Android scheduling/output buffering can delay the stop. Recordings are unmodified.

Pitch and intensity use independent linear scales, specified beside the plot;
they do not use the spectrogram's Hz axis. Formants use the spectrogram axis
(0–5,500 Hz). Estimated pulse markers appear on the waveform only at ≤500 ms spans.

## Actual methods and settings

| Layer | Method / defaults |
|---|---|
| Pitch | Normalized lag autocorrelation on DC-removed signal resampled to 4 kHz; window 3/floor seconds, 10 ms hop; 75–500 Hz. Local maxima ≥0.65 correlation; RMS >0.0001 full scale; parabolic lag refinement. A short-lag preference and previous-frame log-frequency penalty discourage octave errors. Advanced floor 50–300 Hz, ceiling 150–800 Hz, ceiling > floor. This is a causal continuity heuristic, not Praat's path search. |
| Formants | Windowed-sinc low-pass resampling to twice ceiling; first-order pre-emphasis from 50 Hz; Gaussian-like window, 25 ms parameter / 50 ms support; Burg LPC order 10 (maximum 5 formants); nominal 6.25 ms hop rounded to samples. Default ceiling 5,500 Hz, alternative 5,000 Hz. Durand–Kerner roots with bounded iterations. Display first four ordered candidates; reject nonconvergent frames, poles outside unit circle, frequencies outside 50..ceiling−50 Hz, bandwidth >700 Hz and candidates separated by ≤50 Hz. Rejected candidates retain their rank as unavailable. No track interpolation. |
| Intensity | 40 ms Hann-weighted mean-square, after unweighted frame DC removal; 10 ms hop. `10 log10(power / 1)` dBFS. A full-scale sine has about −3.01 dBFS. Digital silence is unavailable in the logarithmic display. No SPL calibration. |
| Estimated Pulses | Contiguous pitch regions with correlation ≥0.85; seed at an absolute extremum near the region midpoint. Search both directions within 0.8–1.2 local periods using waveform cross-correlation ≥0.85, one-period neighborhoods, parabolic sub-sample refinement. Linear fractional-sample interpolation; correlation sampled at roughly 8 kHz. Stop at missing/low-confidence voicing or correlation. Conservative edge coverage; no extrapolation. Acoustic cycle estimates, not physical glottal-closure measurements. |
| Spectrogram | Real FFT of 5 ms Hann windows, zero padded to next power of two ≥twice window length; nominal 5 ms hop, increased for long files to cap at 20,000 columns. Grayscale fixed −80..−10 dB relative FFT power; bins at actual FFT frequencies through 5,500 Hz. No invented image data. |

All timestamps derive from sample indices and frame centres. The finite-support,
256-phase windowed-sinc resampler differs from Praat's resampler. These methods
are Praat-inspired estimates, **not demonstrated Praat equivalents**.

Cursor measurements use the nearest frame within 6 ms, including its missing
state. Selection pitch/formant values are arithmetic means of valid frame centres
inside the interval; missing pitch is never averaged as zero. Intensity averages
linear frame powers (digital-silence frames contribute zero), then converts to
dBFS. Windows can extend outside the selection: these are summaries of the
recording's frame analysis, not a reanalysis of a cropped interval. Selection
shorter than a layer's window support is unavailable. An interval mean formant is
not a steady-state vowel measurement. F1–F4 are LPC candidate ranks, which can
contain missing values and change with speaker, ceiling, noise and phonation.

## Lifetime and performance

An Android ViewModel retains audio, calculated layers and navigation over rotation.
Background coroutines perform all numerical processing, WAV loading, waveform
envelope creation and spectrogram bitmap creation. Frame/resampling/root loops
cooperatively check cancellation. Changing recording cancels obsolete work.
Settings invalidate only pitch+pulses or formants as appropriate. Layer toggles
reuse completed data; gestures never rerun analysis. Cache holds only the open
recording; leaving the analyser releases references. No database/service.

Waveform uses 128-sample min/max blocks with exact sample handling at pixel
boundaries. Drawing queries visible track ranges by binary search, limits formant
markers to screen density, and limits contour drawing to about one segment/pixel.
The spectrogram draws only visible columns from 1,024-column bitmap tiles, avoiding
oversized GPU textures for long recordings.

The audio buffer is float PCM: about 55 MiB for five minutes at 48 kHz. Formant
resampling and images add memory. Analysis refuses >10 minutes or audio buffers
exceeding one third of the device's VM heap limit, with a visible error and original
WAV retained. Several-minute runtime and memory on an ordinary phone remain
unmeasured; JVM timings are not phone performance evidence.

## Reproducing the scientific comparison

The optional Praat comparison needs Python 3 with NumPy, SciPy, and
`praat-parselmouth`. These are validation tools only; they are not Android runtime
or build dependencies.

Run the following commands from the repository root:

```powershell
python .\tools\validate_acoustics.py --prepare .\build\acoustics-validation
$env:RESON_ANALYSIS_FIXTURES = (Resolve-Path .\build\acoustics-validation).Path
.\gradlew.bat :app:testDebugUnitTest --rerun --no-daemon --max-workers=1 `
  '-Dorg.gradle.jvmargs=-Xmx1024m' '-Pkotlin.compiler.execution.strategy=in-process'
Remove-Item Env:RESON_ANALYSIS_FIXTURES
python .\tools\validate_acoustics.py --compare .\build\acoustics-validation
```

The exporter runs the actual Kotlin production functions and writes per-frame CSVs.
Without the environment variable, the opt-in exporter test is skipped. Additional
supported WAVs can be placed in the fixture directory before the export run.
Generated WAVs are not Gradle source inputs; do not regenerate them during a running
comparison.
