# Offline IPA Studio

Available from **IPA Studio** in the recording studio and analyser. Returning
to the analyser retains its viewport, layers and selection. Opening IPA Studio
stops playback. Its recording-studio entry is disabled while capturing so the
recording controls remain accessible. Package ID and recording storage are unchanged.

## Type, Chart and Search

**Type** is the default. The editor stays above the symbol area and shares one
ViewModel `TextFieldValue` across all three modes. Mode/category changes never
replace the draft or its caret/selection. The initially one-line editor grows to
four lines, then scrolls internally. With the software keyboard open it is capped
at two lines; short viewports use one line. The draft menu (⋮) contains Copy,
Paste and confirmed Clear. Saved status is secondary; save errors remain explicit.
A labelled Hide keyboard action appears while the IME is visible.

- Type uses rounded rectangular tonal keys with 24 sp IPA glyphs and at least
  52 dp targets. At default font scale, the adaptive grid gives six columns at
  360 and 411 dp widths with 12 dp outer margins. Larger font scales increase
  minimum key size and reduce column count. Symbols scroll vertically.
- Two dropdowns choose family and group: Consonants by manner; Vowels by
  backness (including near-front/near-back); Modifiers for diacritics, stress /
  length / boundaries, and tones; More for non-pulmonic and other consonants.
  The groups partition all 173 catalogue entries without modifying the data.
- Tap a Type key to insert immediately at the caret or replace selected text.
  Material press feedback confirms the tap. Long press opens the inspector,
  also exposed as an accessibility action. The latest six recent symbols appear
  below the active keys; the existing persisted history still retains twelve.
- Chart offers a category dropdown. Pulmonic consonants show one selected place
  with labelled manner rows, voiceless left/voiced right. Vowels show one backness
  column with ordered height rows, unrounded left/rounded right. Left/right pair
  order stays LTR even with Arabic drafts. Empty slots have no dash; impossible
  pairs have subtle shading and accessibility descriptions. These are scientific
  tables, not spatial acoustic plots. Other groups retain catalogue subcategories.
- Chart taps inspect without editing. The bottom sheet shows nonempty metadata,
  Unicode codepoints, description and examples, plus an explicit Insert button.
  The always-visible Type segment returns to direct input.
- Search remains entirely offline, matching glyph, name, features, aliases and
  codepoints. Examples: `ə`, `schwa`, `voiced bilabial`, `uvular`, `U+0259`,
  `open-mid central rounded`. Compact rows show name/features; tap the row for
  details or its separately labelled Insert action to edit. No matches produces
  an explanatory empty state.
- Unicode insertion and atomic saves are unchanged. Combining marks display with
  a dotted circle but insert only their original character. Affricates and
  multi-character sequences are inserted literally without normalization.
  Position tie bars between consonants and diacritics after their base.
- Copy copies the complete draft; Paste uses the saved selection/caret. Native
  selection and clipboard controls remain available. Confirmed Clear saves empty
  text. A new process restores draft text with the caret at its end, as before.
- Safe drawing and IME insets keep content out of system bars and the keyboard.
  The normal viewport keeps editor/navigation above an independently scrolling
  grid. Below 280 dp available height the whole workspace can also scroll, so
  large fonts / landscape IME cannot make lower controls permanently unreachable.
  No horizontal scrolling surfaces or forced landscape are used.

Reference review used [IPA Keyboard by Chuehfu Tech](https://play.google.com/store/apps/details?id=com.edwardgreve.ipakeyboard),
[Gboard IPA layout guidance](https://www.uni-erfurt.de/en/faculty-of-philosophy/studienrichtungen-philologien/english-and-american-studies/tools-fuers-seminar/international-phonetic-alphabet-ipa),
and the [Complete IPA Keyboard design paper](https://www.internationalphoneticassociation.org/icphs-proceedings/ICPhS2015/Papers/ICPHS0284.pdf).
The design takes grouped key pages and progressive reference disclosure as
inspiration, using Reson's existing Material theme, font and catalogue.
The brief's phone screenshots were not attached to this request; layout problems
were assessed from its descriptions and the existing composition.

## Data and fonts

The bundled `app/src/main/assets/ipa_catalogue.json` contains all **173** desktop
catalogue entries, including aliases, examples, Unicode metadata and categories.
It also contains desktop consonant/vowel chart assignments. Nothing is downloaded
at runtime and no Python, server, speech recognition, AI or synthesis is involved.

The JSON catalogue and chart assignments are checked into this project and read
locally at runtime. They are a snapshot of Reson's phonetics catalogue; the desktop
source is not a build or runtime dependency. Run `python tools/test_bundled_ipa.py`
from the repository root to check the bundled schema, symbol coverage, and chart ordering.
Android parses the asset with platform JSON; no runtime library was added.

The existing `assets/fonts/NotoSans-Regular.ttf` is bundled as `res/font/noto_sans.ttf`
with its existing SIL Open Font License in `assets/Noto-OFL.txt`. Font cmap inspection
found coverage for every catalogue character except `↗` U+2197 and `↘` U+2198;
those use Android font fallback. Device font shaping/fallback can vary, particularly
for stacked combining marks and tie bars. Labels and themes use existing Material
tokens and persisted System/Light/Dark preference; no branding assets were changed.

## Draft persistence

Draft text lives separately in private `filesDir/ipa-draft.txt`. A single ordered
background writer writes UTF-8 to a same-directory temporary file, syncs it, and
atomically replaces the saved draft. A failed replacement retains the previous
file; load/save errors are shown instead of silently presenting an empty saved
draft. The screen reports Saving, Saved or a save error. Navigation keeps the
writer alive; ViewModel disposal drains pending writes. Force-stopping a process
before a save finishes can lose those pending edits; **Saved on device**
indicates the latest edit completed. Recents use lightweight Android preferences.
No draft text is sent to a service. Android clipboard destinations are user-chosen.

## Verification

- `python tools/test_bundled_ipa.py` validates the 173-entry asset schema,
  categories, consonant/vowel reachability, feature coordinates, and pair ordering.
- JVM tests cover keyboard category partitioning and feature grouping, offline
  search, cursor and reversed-selection insertion, combining marks, affricates,
  surrogate pairs, long mixed Arabic/IPA/emoji text and exact atomic draft I/O.

JVM tests do not establish real Compose rendering, Android clipboard/long-press
behavior, touch selection retention, IME/landscape layout, screen-reader behavior
or glyph shaping. Device QA should cover 360/411 dp, increased font scale and both
themes, including a selected range and middle caret across Type → Chart → Search →
Type. It should also cover long-draft scrolling, keyboard dismissal, Copy/Paste,
confirmed Clear, rotation and process restart. Analyser and TextGrid workflows
need a device smoke test.
