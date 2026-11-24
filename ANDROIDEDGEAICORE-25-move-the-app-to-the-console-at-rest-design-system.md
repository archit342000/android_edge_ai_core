---
id: ANDROIDEDGEAICORE-25
title: "Move the app to the Console at Rest design system"
status: in-progress
created: 2026-11-24
updated: 2026-11-25
---

## Description
Make Edge AI Core comply fully with the Console at Rest design system v1.2 (the `console-at-rest` repo: `RULEBOOK.md`, `tokens.json`, `tokens.css`, `reference/console-at-rest.html`). Today the app is a stock Material 3 app (blue-grey palette, Roboto, 24dp cards, system icons). The owner wants proper compliance, not a recolour, in this one ticket. "Done" is the Part 10 checklist of the rule book passing for every screen.

### Approved design (2026-11-24)
1. **Stay on Views.** No Compose, no new libraries. Material 1.11.0 already has per-corner shapes, bottom sheets, snackbars, `NavigationRailView` and `NavigationView`.
2. **Token and style layer in `res/`.**
   - Day and night colours from `tokens.json` (rgba converted to ARGB), the eight spacing steps, touch sizes, per-corner shape appearances (small, control, panel, sheet), the text styles the app uses, motion durations and the three curves.
   - Fonts bundled as static TTFs in `res/font`: Sora 600/700, Instrument Sans 400/500/600, JetBrains Mono 400/500, with their licence texts.
   - `Widget.Console.*` styles for every piece used (Main, Quiet, Outline, Plain and Danger buttons, switch, pick-one circles, text box, top bar, bottom bar, rail, navigation panel, row, fold-open box, code box, notice strip, brief message, sheet).
   - The theme maps every Material 3 colour role to a token so nothing falls back to Material defaults. No `md_theme_*` names remain.
   - A JVM test compares the Android resources with a snapshot of `tokens.json` kept in the test resources and fails on drift.
   - Shadow values exist only in `tokens.css`; add them to `tokens.json` in the console-at-rest repo (with a change-log line) and mirror them here.
3. **Layouts by width.** Under 600dp: bottom bar. 600 to 1023dp: 70dp icon rail. 1024dp and over: 252dp navigation panel. Reading text capped at 704dp, settings and forms at 640dp. Edge to edge with insets; the keyboard never covers the focused field; Back returns to the Server tab before leaving the app. Each screen has a top bar with its name.
4. **Pages.**
   - Server is the app's home, an open screen: status light and word, headline and server address sit on the sphere with a fade behind each line; the reading panel starts below; the sphere drops to reading strength after 120dp of scroll.
   - Backend and Tokens are reading columns.
   - No grouping cards: sections use typewriter labels, tick rules and standard rows.
   - Tests and logs live in one fold-open box, shut by default; logs use the dark code box.
   - Three outline icons (2dp stroke, 24 grid) replace the system drawables in the navigation menu.
5. **Dot sphere.** A Canvas view ported from the reference page's script: 250 dots on a phone (600 at 1024dp and over), golden-angle placement, the five dot colours, still stars at night, leans toward touch, ripples on a commit (server start, token generated), pulses while a model loads or a test runs, drawn once and still when the system animator scale is 0, runs only while the activity is resumed. The maths lives in a plain Kotlin class with a unit test.
6. **Glass and solid.** Android 12 and later (API 31): frosted floating pieces and the lightly blurred content panel, done with a blurred copy of the sphere behind each piece (`View.setRenderEffect`); bars must not overlap scrolling content. Below API 31: the solid values. Both modes must be selectable for checking.
7. **States.** Keyboard focus ring (2dp, 2dp away, blue) on everything pressable; a reason beside every switched-off control whose cause the person can change (no model selected, server not running); busy buttons that say what they are doing; an error notice strip with one action; empty states; long paths cut with an ellipsis; nothing clipped at 200% text; no motion when the system asks for none.
8. **Behaviour the rule book requires.**
   - Confirming bottom sheets only for the irreversible (delete token, revoke access) with a named red action; the "Generate new token?" confirm is removed.
   - Brief messages (2.6 s, bottom centre) replace toasts.
   - Settings apply at once and show "Saving" then "Saved"; the context size is checked when the box loses focus, with the reason beneath it.
   - An explain card with "Not now" as an equal choice comes before each system permission prompt and the battery exemption request, asked at the point of use rather than at launch.
9. **Words.** All user-visible text moves to `strings.xml` and follows Part 9: sentence case, buttons are a verb and a thing, no "successfully", errors say what happened and what to do, statuses read like "Ready" not "Idle". To make rewording safe, `InferenceService` sends a status kind (a plain Kotlin enum with a unit test) as a new extra and `MainActivity` switches on the kind, not on words in the text.
10. **Code placement.** New Kotlin goes in new files; `MainActivity.kt` changes only where a function must change. Existing view ids stay wherever the view still exists, because `scripts/device.sh` taps by id.
11. **Docs.** Update `README.md` (tab and button names), `docs/standards/android.md` (UI section names the design system and its rules), `docs/architecture.md` (new classes), `docs/pitfalls.md` (item 15 fixed by `values-v27`), `docs/testing-on-device.md` and `scripts/device.sh` if the status log line changes.

### Scope
In scope: everything above, plus the small `tokens.json` addition in the console-at-rest repo.

Out of scope:
- Jetpack Compose (rejected: rewrites a 1,157-line activity with no UI tests and needs dependencies the app does not have).
- A recolour that keeps the Material layout and `md_theme_*` names (rejected: no sphere, fonts or squared corners, so the app would not be in the family).
- A generator script for the token resources (rejected: more custom code than hand-written XML plus the parity test).
- The foreground-service notification's look and the launcher icon (the rule book lists phone notifications as not covered; only the notification's wording changes).
- REST API, inference, token logic, `targetSdk`, dependency versions.
- Lifting the Android pieces into the console-at-rest repo as shared components (its README says that happens when a second app needs them).

### Architecture notes (2026-11-24)
Findings, checked in the code:
- Rule book: an app must have all five signatures (`RULEBOOK.md:77`); the sphere is never removed from an app (`:275`); checklist item (`:652`). Android rules (`:370-379`). Open, reading and working screens (`:232-242`). Words on the sphere (`:244-275`). Pop-ups only confirm the irreversible (`:491`). Explain before a permission prompt (`:496`). Icons (`:210`). Notifications not covered (`:702`).
- Solid mode makes the content panel opaque (`tokens.css:164-165`), so without an open screen the sphere would never show; this is why Server is the open home screen.
- `tokens.json` has no shadow values; they are at `tokens.css:56-57` and `:135`.
- Sphere script: reference page, about lines 2495 to 2580 (250 dots when touch or narrow, `theta = i * 2.399963229728653`, perspective 800, ripple on pointer down).
- App UI today: one activity, `res/layout/activity_main.xml` (643 lines), three pages toggled by visibility (`MainActivity.kt:183-209`), theme parent `Theme.Material3.DayNight.NoActionBar` (`res/values/themes.xml:2`), grouping cards at `activity_main.xml:39-44`, `267-272`, `488-494`, system icons in `res/menu/bottom_nav_menu.xml`.
- `MainActivity.kt:937-970` enables controls by matching words in the status text ("loaded", "Error", "Idle").
- The service's status text comes from `strings.xml` (`InferenceService.kt:174-176`, `:256-262`), and `scripts/device.sh:93-95` waits for the log line "Model loaded successfully". Keep that log line or change the script and docs together.
- `scripts/device.sh:31-36` taps by resource id; `docs/testing-on-device.md:49` names `nav_backend`, `rb_cpu`, `nav_server`, `switch_enable_server`; the script also taps `nav_tokens` and `btn_generate_token` (`:107`).
- Dialogs: generate (`MainActivity.kt:369-376`, reversible), delete (`:394`), revoke (`:513`). Toasts at `:387`, `:401`, `:432`, `:438`, `:460`, `:467`, `:477`.
- Permissions and the battery exemption are requested at the end of `onCreate` (`MainActivity.kt:354-355`, `:535-570`).
- Material 1.11.0 AAR contains `cornerSizeBottomLeft`, `Widget.Material3.BottomSheet`, `Widget.Material3.Snackbar`.
- Repo rules: Views, Compose needs the owner (`docs/standards/android.md:29`); newer theme attributes go in `values-v<API>` (`:32`); `MainActivity.kt` is large, touch only what is needed (`docs/standards/coding.md:12`); new source classes get a test class, framework classes excepted (`docs/standards/testing.md`); checks are `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh`.
- The test emulator `claude_edgeaicore_api34` and `scripts/device.sh screenshot` are available for screenshots.

Decisions by the owner: full compliance over a smaller ticket; one ticket; sphere, fonts, real blur, the open home screen, tablet and wide layouts, status rewording and permission cards are all in.

Unverified:
- Android has no backdrop blur for Views; the blurred-copy technique is unbuilt.
- Font licences (believed OFL) and the APK size they add.
- The sphere's cost while inference runs (measure tokens per second with it on and off).
- Legibility of text over the translucent panel.
- Whether every Material widget honours per-corner shapes.

How it was produced: panel path (one scout, three Haiku architects, one critique round), then revised after the owner asked for full compliance. The panel had proposed a colours-only ticket; the rule book rules that out.

## Acceptance criteria
- [x] No raw colour, size, corner, shadow or duration in layouts, styles or UI code; all come from the token resources, and the parity test against the `tokens.json` snapshot passes.
- [x] Every shape squares its bottom-left corner, or is a listed exception (circles, bottom sheet, edge-to-edge surfaces).
- [x] Only Sora, Instrument Sans and JetBrains Mono appear, each in its own job; typewriter labels only on section names, figures, times and status.
- [x] Blue appears only on things that can be pressed or are selected; at most one Main button per screen; no gradients, glows or emoji icons.
- [ ] The dot sphere is present on every screen at the right strength: open at the top of Server, reading strength after scrolling and on Backend and Tokens; still stars at night; it reacts to touch, commits and work.
- [x] Words on the sphere have a fade behind each line; everything longer is on a panel.
- [x] Day and night both pass, on a phone width, a 600 to 1023dp width and a 1024dp or wider width, with the bottom bar, rail and navigation panel respectively.
- [x] Edge to edge; touch targets at least 48dp; confirms rise from the bottom; Back undoes the newest layer and leaves the app only from Server; the keyboard never covers the focused field.
- [x] Glass mode works on API 31 and later and solid mode below it; both were checked.
- [x] With system animations off nothing moves, including the sphere.
- [x] With a keyboard everything is reachable and the focus ring is visible; text contrast is at least 4.5 to 1; nothing is clipped at 200% text size.
- [x] Every switched-off control with a changeable cause says the reason; buttons show a busy state; empty, loading, error and long-text states exist.
- [x] All user-visible text is in `strings.xml` and follows Part 9 of the rule book; `MainActivity` no longer decides state from words in the status text.
- [x] Only delete token and revoke access ask for confirmation; brief messages replace toasts; an explain card with "Not now" precedes each permission prompt.
- [x] Server start, the six tests, token generate, copy, delete, backup, restore and revoke still work, and `scripts/device.sh` flows still pass.
- [x] `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass with no new lint baseline entries; README and docs match the new UI.
- [x] `tokens.json` in the console-at-rest repo carries the shadow values, with a change-log line.

## Plan
**Date:** 2026-11-25  **Status:** Validated - minors logged

### Objective
Make every screen of Edge AI Core pass the Part 10 checklist of the Console at Rest rule book v1.2, by replacing the stock Material look with a token-driven theme, the bundled typefaces, the dot sphere, the rule book's layouts, states, behaviours and wording, without changing inference, the REST API or token logic.

### Context
- Requirement: this ticket's Description and Acceptance criteria. The design-system files are in the `console-at-rest` repo (`RULEBOOK.md`, `tokens.json`, `tokens.css`, `reference/console-at-rest.html`).
- Assumptions:
  1. **Where numbers come from.** The design system has two kinds of number. Shared values (colours, lettering, spacing, corners, shadows, speeds, curves, and the sizes in `tokens.json`) are tokens. A piece's own geometry and movement (the switch's 48 by 28 track, a keyframe's 12 rise, the focus ring's 2 and 2, the switched-off 0.45, the sphere script's coefficients) is written as plain numbers in the reference page, which the rule book says wins on how a piece looks and behaves. The app mirrors that: token values go in token resource files checked against `tokens.json`; piece numbers go in one piece resource file, each with a comment naming the reference selector, keyframe or script line it was copied from. Nothing else in the app holds a raw value. Acceptance criterion 1 is read this way. Only the shadows, which Android cannot draw from the CSS, need an addition to the design system (Decisions 3).
  2. The Server headline is the app name; the status light and word sit above it and the server address below it.
  3. The enable-server control stays a switch (`switch_enable_server`): on or off, takes effect at once.
  4. The four pick-one settings (backend, speculative decoding, vision encoder, audio encoder) are each two to four short choices, which rule book 7.3 assigns to the side-by-side pick-one (the Description's style list says "pick-one circles"; this replaces it). They are built from the existing `RadioGroup` and `RadioButton` widgets laid out in a row and styled as segments, so ids and selection code are unchanged.
  5. An empty list inside a page (no authorised apps) is one plain line, not the dead-end layout: rule book 2.7 keeps the small drawing for empty screens and "never beside real content".
  6. The media and storage runtime permissions have no point of use (every file comes through a document picker), so they are no longer requested. Their manifest entries stay; removing them is a follow-up.
  7. A busy button shows a small spinner, as rule book 7.1 and the reference page have it, although Part 4 lists only three loops.
  8. Translucent surfaces get no elevation shadow: Android draws a shadow through a see-through surface. Their edge tells them apart. Solid pieces that float (sheets, brief messages) use the lifted elevation, and in solid mode the panels and cards use the resting elevation.
  9. The checklist's offline state does not apply (the app's only connection is to its own server, whose failures show as the error state), and no page loads content, so there are no placeholder shapes; waiting shows as busy buttons, a progress bar, the working dots and the pulsing status light.
  10. Fonts are under the SIL Open Font License; the implementer confirms it from each font's licence file before committing it and stops if one is not. Bundled fonts and the `tokens.json` snapshot are assets and data, not "library code" (`docs/standards/dependencies.md`).
- Rules that apply (`AGENTS.md`, `docs/standards/`):
  - Views with Material and ConstraintLayout, no Compose; tabs are pages toggled by visibility; all user text in `strings.xml` (`android.md`).
  - `MainActivity.kt` is large, touch only the functions needed; constants in the companion object (`coding.md`, `AGENTS.md` 3).
  - New or changed source classes get a test class; framework classes are excepted; unit tests cannot call `android.*` (`testing.md`).
  - New extras and actions go in `InferenceService.companion`; status goes by app-private broadcast (`android.md`).
  - Never log a token or any part of one; no literal log tags; do not catch `CancellationException` without rethrowing (`security.md`, `errors-and-logging.md`).
  - No change to the REST contract or to SharedPreferences names or existing keys (`review.md` 8).
  - Docs stay under 200 lines each and match the code (`scripts/check_docs.sh`, `docs.md`). Work on a `feature/` branch from `develop`, PR into `develop` (`gitflow.md`).
- Current behaviour in the code:
  - **One layout.** `MainActivity.onCreate` inflates `res/layout/activity_main.xml`, finds views by id and toggles `page_server`, `page_backend`, `page_tokens` from `bottomNav.setOnItemSelectedListener`; titles are literals. `bottomNav` is typed `BottomNavigationView`. Two dead views (`ll_pending_requests`, `tv_pending_label`) are hidden in `onCreate`.
  - **Control state is written in many places.** `updateUIForStatus` enables controls by matching words in the status text ("loaded", "Error", "Idle"). `onCreate` (restored model), the switch listener, `startInferenceService` (invalid context size), `updateTokenUI`, `startAudioRecording`, `runTestAudio`, `runTestVision`, `runTestInference` and `copyModelFile` also set `tvStatus`, `isEnabled` or `isChecked` directly. Tests and file work run in unscoped coroutines.
  - **Service.** `InferenceService.onStartCommand` handles `ACTION_STOP` by stopping the server and calling `stopSelf`. Otherwise it sends "service starting", starts the server, and launches the model load in `serviceScope`; the load cannot be interrupted while the engine initialises, skips itself when `isModelLoading` is set, and reports through `sendStatusBroadcast` (text from `strings.xml`). The crash-loop and missing-model exits send their status and call `stopSelf`. A sticky restart arrives with a null intent and restores the saved model, or has none. `onDestroy` cancels the scope. The "loaded" status is also logged with `Log.i`, and `scripts/device.sh` (`cmd_start`) greps the service's log for it and for three failure lines.
  - **Status reaches the activity only by broadcast**, and only while it is resumed (`docs/pitfalls.md` 23).
  - **Settings** (context size, speculative decoding, encoder backends) are saved only in `startInferenceService`; the backend choice is never saved.
  - **Dialogs and toasts.** `setupTokenListeners` and `updateAuthorizedAppsUI` use `AlertDialog` and `Toast`; rows are inflated from `android.R.layout.simple_list_item_2`.
  - **Permissions.** `checkAndRequestPermissions` and `requestIgnoreBatteryOptimizations` run at the end of `onCreate`.
  - **Text.** `MainActivity.kt` has 57 `appendLog` calls with literal text, some with a cross-mark symbol, two uses of a token prefix (the log line in `generateNewToken` and the row text in `updateAuthorizedAppsUI`), and two literal log tags (`performBackup`, `performRestore`); `app/lint-baseline.xml` holds 31 `SetTextI18n` and 12 `HardcodedText` entries.
  - **Theme.** `Theme.LiteRTLMServer` in `res/values/themes.xml` and `res/values-night/themes.xml`, colours `md_theme_*` in `res/values/colors.xml`.

### Approach
- **Resources first, then code.** Values, fonts and styles land before any layout uses them, so each later step only references names.
- **Two value files, one check.** `tokens_*.xml` files mirror `tokens.json` under matching names (`console_bg`, `space_4`, `radius_control_*`) and are guarded by a parity test. `pieces.xml` holds the piece numbers of assumption 1. One check, run in the steps that add files: a grep finds no colour literal, no `dp`, `sp` or millisecond literal and no bare alpha in layouts, styles, shapes, drawables, colour lists, animators or the new Kotlin files (a vector's viewport and path data are exempt). Both value files carry `tools:ignore="UnusedResources"`, so mirroring a value the app does not use yet adds no lint issue.
- **One set of page layouts, three frames.** The three pages are separate layout files included by three activity layouts chosen by width qualifier (`layout`, `layout-w600dp`, `layout-w1024dp`). All three navigation widgets use the one menu resource and call one `showPage(itemId)`.
- **Sphere.** One plain Kotlin model owns the dots and the animation state; views only draw it. A full-screen view draws the background. On API 31 and later, one more view per frosted piece draws the same model clipped to that piece with a blur `RenderEffect`. Per the reference page the content panel is lightly blurred, the navigation panel and rail are frosted, and the phone bottom bar uses the nearly solid `pop` colour, so nothing scrolls under a frosted piece.
- **Solid mode** is used below API 31, and can be forced with a launch extra on debuggable builds so it can also be checked on the API 34 emulator.
- **The service owns the status.** A plain Kotlin object, `ServerStatusStore`, holds the current status (a kind and a detail text) and the current reporter (the service instance allowed to report, or none) behind one lock. It is the only place a status is accepted or dropped.
  - `begin(reporter)` makes that instance the reporter; `onStartCommand` calls it for every start that is not a stop.
  - `publish(reporter, kind, detail)` stores only if `reporter` is the current one, and says whether it did. The service sends its broadcast, and logs the "loaded" line, only when it did.
  - `stop()` clears the reporter and stores off. Only the `ACTION_STOP` branch calls it, then sends the broadcast.
  - `end(reporter)`, from `onDestroy`: for the current reporter it clears the reporter and turns starting or ready into off (then the broadcast is sent); an error stays; any other reporter is ignored.
  - Who wins: a load that ends after a stop has no reporter and is dropped. After stop then start on a new instance, the new instance reports and anything late from the old one is ignored. If a start reaches the same instance, it reports again and its running load's result is stored. Two starts with no stop keep one reporter. Error exits publish, call `stopSelf`, and `end` leaves the error. A destroy during a load stores off and drops the late result. A start with no model to load publishes an error. After process death the store is off.
- **The activity renders from state, in one place.** Everything that decides a control's enabled or checked state, the status light, word and detail, reason lines, busy labels, the error strip and the explain card moves into one function, `render()`, which is the only code that sets them. It reads a small state and asks two plain functions what to show:
  - State: the store's snapshot; a pending action (start or stop, set by the switch and cleared by the next status signal and by `onResume`); whether a model is selected; whether a token exists; the set of busy tasks (model copy, each of the six tests, backup, restore); permission facts and answered flags; whether the microphone card was asked for.
  - `ControlsPolicy.compute(state)` returns every control's state. The effective kind is starting while a start is pending, off while a stop is pending, otherwise the stored kind. Switch: checked when starting or ready; enabled when ready, or when off or in error with a model selected and no copy running; otherwise, when off or in error, its reason says to select a model first or to wait for the copy; while starting it shows no reason, because the status says so. Each test button: enabled when ready and not itself busy; otherwise its reason says to start the server first. Select model: enabled unless a copy is running. Copy token and delete token: enabled with a token, else the reason says to generate one. Backup and restore: enabled unless themselves busy. Working (dots, pulsing light, sphere working) while starting or while any test or the copy is busy. Error strip while in error, showing the detail, with one action: try again (the same path as switching on) when a model is selected, otherwise select model.
  - `ExplainCardPolicy.next(state)` returns the card to show, by the rules below.
  - Every event ends in `render()`: `onCreate`, `onResume`, each status broadcast, the switch, a busy task starting or ending (ended in a `finally`), a token change, a model copy finishing, each permission result. A busy button therefore stays disabled across `onResume`, and no code path can leave a control in a state the policy would not choose.
- **Permission asks are separate from every action.** An explain card is an inline card with two equal buttons (the allow action and "Not now").
  - A Server-page card shows only while the stored kind is ready (the point of use), for a permission that is missing and whose card has not been answered. Notifications count as missing only on API 33 and later. Notifications come before the battery exemption.
  - Any answer hides that card for good: "Not now", a denied result, returning from the battery screen still not exempt, or a launch that throws (logged) all store an answered flag. A grant makes the permission no longer missing.
  - Both buttons are disabled from a press until its result arrives.
  - Starting the server never shows or waits for a card.
  - The microphone card shows only in answer to pressing the audio test without the permission. The test starts only from the grant callback, or at once when the permission is held. A denied result hides the card and shows a brief message that the microphone is off for the app and can be turned on in system settings.
- **Less motion means none.** Every movement is an animator, so it follows the system animator scale. The looping ones (working dots, status light pulse, busy spinner) and the sphere's frame loop are not started when `ValueAnimator.areAnimatorsEnabled()` is false.
- **Bar icon colours are set in code**, not in `values-v27` as the Description's item 11 says: one place covers day, night and every API level, and it removes both lint errors of `docs/pitfalls.md` 15.
- **New code in new files**; `MainActivity` keeps its fields and functions and changes where state is written or behaviour changes.
- Rejected: Compose (owner rule, large rewrite); a blur library (new dependency; the only thing behind a frosted piece is the sphere); `MaterialButtonToggleGroup` for the pick-one settings (changes widget classes and selection code); adding every piece number to `tokens.css` (the reference page itself keeps them as plain numbers); a new intent extra for the status kind (a status reached while the app is in the background would still be lost).

### Out of scope
- REST API, inference, token storage, conversation storage, `targetSdk`, dependency versions.
- The foreground notification's look and the launcher icon (not covered by the rule book); only the notification's wording changes.
- Moving the HTTP test client out of `MainActivity`; tying test and copy coroutines to the activity (the busy set is lost when the activity is recreated, so a rotation during a test or a model copy lets a second one start, as today).
- What an old model load does after a stop besides reporting (it still finishes in the background, as today).
- Lifting the Android pieces into the console-at-rest repo as shared components; removing the unused permission declarations.

### Steps
#### Step 1: Add the shadows to the design system
- **Status:** todo
- **Depends on:** none
- **Files:** in the `console-at-rest` repo: `tokens.css` (modify), `tokens.json` (modify), `RULEBOOK.md` section 2.5 and change log (modify)
- **Change:** Add a `shadow` block to `tokens.json` mirroring `--shadow` and `--shadow-lift` for day and night. Add the two Android elevation numbers the owner agreed to (Decisions 3), resting and lifted, to `tokens.css` first (named, with a comment) and then to `tokens.json`. Pick starting numbers by eye against the reference page; step 3 may adjust them on the emulator and then changes these files and the app's snapshot together. Add one sentence to rule book 2.5 that Android draws the two shadows as these elevations, and one change-log line. Commit on a branch in that repo and open a PR there.
- **Verification:** `tokens.json` parses as JSON; its day and night shadow strings equal the ones in `tokens.css`; both files carry the same two elevation numbers.

#### Step 2: Value resources, fonts and the parity test
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/res/values/colors.xml` (modify: add the token colours; the `md_theme_*` names stay until step 3 so the app still builds), `app/src/main/res/values-night/colors.xml` (create), `app/src/main/res/values/tokens_dimens.xml`, `tokens_numbers.xml`, `pieces.xml` (create), `app/src/main/res/font/*.ttf` (create: Sora 600 and 700, Instrument Sans 400, 500 and 600, JetBrains Mono 400 and 500), `app/src/main/assets/licenses/` (create: the three licence texts), `app/src/test/resources/console-at-rest/tokens.json` (create: snapshot from step 1), `app/src/test/java/com/aanand/edgeaicore/ConsoleTokensTest.kt` (create)
- **Change:**
  - Token files: every colour in `tokens.json` for day and night (rgba converted to `#AARRGGBB`, including the `solid` variants and the five dot colours); the eight spacing steps; page padding, touch size, measure, rail and panel widths, status light size; the four corner sets as per-corner dimens; the text sizes, weights and tracking; the five durations; the two elevations; and every numeric value of the `glass` and `sphere` blocks (dot counts, size factor, strengths, phone factor, open-until-scroll distance, fade strength and blur, padding ranges, patch radius). Fractions are float resources.
  - `pieces.xml`: the numbers of each piece the app builds, copied from the reference page with a comment naming the source: button, switch, side-by-side pick-one, text box, row, top bar, content header, bottom bar, rail, navigation panel, fold-open box, code box, notice, brief message (including its 2.6 s), sheet, status light, tick rule, focus ring, switched-off strength, the settings measure (640), the named movements used, hover and press movement, and the sphere script's numbers (radius factors and minimum, stretch factor and cap, golden angle, perspective, spin, drift, breathe, wave, ripple, dot distance spread, dot size range, alpha floor, offset and caps, star dimming).
  - Fonts: download the seven static files from their official sources, rename them to lowercase with underscores as resource names require (for example `sora_semibold.ttf`), and keep their licence files with a note of the original names.
  - `ConsoleTokensTest` reads the snapshot with Gson and the token resource files from disk and asserts every mapped value matches. It asserts a contrast ratio of at least 4.5 for each text colour on each surface it is used on, day and night: solid surfaces as they are, and each translucent surface composited over its worst case, the page background with each dot colour blended in at reading strength. It asserts that the sphere numbers in `pieces.xml` that `tokens.json` gives as formulas in words (radius, stretch, golden angle) appear in those formula strings.
- **Verification:** `./gradlew testDebugUnitTest --tests '*ConsoleTokensTest'` passes; changing one colour in `colors.xml` makes it fail; `./gradlew lintDebug` reports no new error (the font files count as unused until step 3 references them). The test finds the resource files relative to the module directory, which is where Gradle runs unit tests.

#### Step 3: Theme, styles, shapes, states, movements and icons
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/main/res/values/themes.xml` and `values-night/themes.xml` (modify), `app/src/main/res/values/colors.xml` (modify: remove `md_theme_*`), `app/src/main/res/values/styles.xml`, `shapes.xml`, `type.xml` (create), `app/src/main/res/drawable/` (create: backgrounds and state lists for each piece, focus ring, status light, switch track and thumb, segment track and segment, three navigation icons, the fold-open arrow), `app/src/main/res/color/` (create: state colour lists), `app/src/main/res/interpolator/` (create: the three curves as path interpolators), `app/src/main/res/animator/` (create: state list animators for hover and press, and the named movements used), `app/src/main/java/com/aanand/edgeaicore/TickRuleDrawable.kt` (create), `ConsoleTokensTest.kt` (modify: assert the three curves' control points)
- **Change:**
  - Keep the theme name `Theme.LiteRTLMServer` (the manifest uses it) on the same Material 3 parent. Set every Material 3 colour role, the shape appearances and the default text appearances from tokens; make the system bars transparent; delete the `windowLightStatusBar` and `windowLightNavigationBar` items from both theme files (step 8 sets the bar icon colours in code); then delete the `md_theme_*` colours.
  - Text styles (greeting, page title, pop-up title, section, heading, body, strong, small, label, code) on the bundled fonts, with line height set through the AppCompat attribute so it works on API 26.
  - Per-corner shape appearances (small, control, panel, sheet).
  - One `Widget.Console.*` style per piece the layouts use: Main, Quiet, Outline, Plain and Danger buttons, switch, side-by-side pick-one, text box, row, top bar, content header, bottom bar, rail, navigation panel, fold-open box, code box, notice strip, explain card, brief message, sheet, progress bar. Each pressable piece has all of the rule book's states: resting, hovered, pressed, selected, keyboard-focused (the ring), switched off, and busy and in error where it can be (the text box in error has the red edge). Every pressable piece is at least 48dp tall at every width: rule book Part 6 sets 48 for phones and Android, so the app uses the token's phone value throughout, not its 44. Hover and press movement come from state list animators (a filled button lifts and shrinks, plain things only change their background).
  - Navigation icons are 24dp outline vectors with round caps and a stroke of 2 in a 24 viewport. `TickRuleDrawable` draws the tick rule from its piece values.
  - Choose the final elevation numbers on the emulator against the reference page; if they differ from step 1, change the design-system files and the snapshot with them.
- **Verification:** `./gradlew testDebugUnitTest --tests '*ConsoleTokensTest'` and `./gradlew lintDebug` pass with no new issues; `grep -rn 'md_theme_' app/src/main` returns nothing; the Approach's raw-value check passes on the files this step adds.

#### Step 4: Status store and service reporting
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ServerStatus.kt` and `ServerStatusStore.kt` (create), `app/src/test/java/com/aanand/edgeaicore/ServerStatusStoreTest.kt` (create), `InferenceService.kt` (modify: the `serviceStatus` field, `sendStatusBroadcast` and its call sites, `onStartCommand`, `onDestroy`, `verifyEngineReady`, companion), `MainActivity.kt` (modify: `statusReceiver`, `onResume`, `updateUIForStatus`, the switch listener's `updateUIForStatus("Idle")` call)
- **Change:**
  - With no `android.*`: `ServerStatus.kt` holds the enum `ServerStatus` (`OFF`, `STARTING`, `READY`, `ERROR`) and the data class `ServerStatusSnapshot` (kind, detail); `ServerStatusStore.kt` holds the object `ServerStatusStore` exactly as the Approach describes (`current`, `begin`, `publish`, `stop`, `end`), one top-level class per file as `kotlin.md` asks.
  - `InferenceService`: remove the unused `serviceStatus` field. In `onStartCommand`, the `ACTION_STOP` branch calls `ServerStatusStore.stop()` and sends the broadcast; every other start calls `begin(this)` first. `sendStatusBroadcast` takes a kind and a text, calls `publish(this, ...)` and sends the broadcast only if it was stored. `onDestroy` calls `end(this)` and sends the broadcast if it stored off. When a start has no model path and is not a crash loop, publish an error ("no model to load") and leave the service as today.
  - Kind per call site: `STARTING` for service starting, restoring, loading model, initializing, verifying, GPU fallback and both ping lines; `READY` for loaded, and for "unsupported model, previous model still loaded" (today its text contains "loaded", so it already behaves as ready; its detail says the new model could not be loaded and the previous one is still running); `ERROR` for crash loop, model missing, verification failed, unsupported model and load failed.
  - The "loaded" log line becomes a fixed English companion constant with the same detail as today, logged only when the publish was stored. The service's other `Log` messages are not changed (`scripts/device.sh` greps three of them).
  - `MainActivity`: `statusReceiver` and `onResume` (after registering the receiver) read `ServerStatusStore.current` and pass it to `updateUIForStatus`, which in this step switches on the kind with today's rules; the switch listener's own call after a stop passes an off snapshot. Step 7 replaces all of this with `render()`.
- **Verification:** `ServerStatusStoreTest` (each test calls `stop()` first, so order does not matter) covers: a publish from a reporter that is not current is dropped; stop then a late publish leaves off; a publish after `begin` is stored; `begin` again by the same reporter keeps its later publishes; `end` turns starting or ready into off, leaves an error, clears the reporter, and ignores a reporter that is not current. `scripts/device.sh up` still reports ready. On the emulator: start, press Home during the load, return after it is ready, and the page shows ready; rotate while ready and it still shows ready; switch off and it shows off and stays off after leaving and returning; start, send the stop from adb while the model is still loading, wait longer than the load takes, and the page still shows off; send stop and then start from adb in quick succession and the page reaches ready; start with the saved model file deleted and the page shows the error and keeps showing it.

#### Step 5: The dot sphere
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/main/java/com/aanand/edgeaicore/DotSphereModel.kt` (create), `app/src/test/java/com/aanand/edgeaicore/DotSphereModelTest.kt` (create), `DotSphereView.kt` (create), `SphereFrostView.kt` (create), `SphereFadeLayout.kt` (create), `SphereFadePatches.kt` (create), `app/src/test/java/com/aanand/edgeaicore/SphereFadePatchesTest.kt` (create)
- **Change:** Port the reference script.
  - `DotSphereModel` (no `android.*`) takes every number it uses in one plain `DotSphereSpec` data class, which `DotSphereView` fills from the value resources, so it holds no literals. It does: golden-angle placement; per-dot distance, size and colour index from a seeded random; radius and stretch from the view size (the phone rules under 600dp of width, the other radius rule with no stretch above it); 250 dots under 1024dp of width and 600 from there; `advance(dtMs)` for spin, drift, pulse and ripple; a touch point it leans toward; `project()` returning dots sorted back to front with position, size and alpha.
  - `DotSphereView` owns one model and draws it and the night stars (dimmed behind reading). It takes a strength (open or reading, times the phone factor) and animates changes of strength. It exposes `ripple()`, `setWorking(Boolean)` and `leanToward(x, y)`. It runs its frame loop only between `onResume` and `onPause`, and when animators are disabled it draws one still frame.
  - `SphereFrostView` (API 31 and later) is attached to the `DotSphereView`, which hands it its one model and invalidates it on every frame. It draws that model translated to its own position, clipped to its piece's shape with `Canvas.clipPath`, with a blur and saturation `RenderEffect`. Below API 31 or in forced solid mode it is not used and its owner has the solid colour.
  - `SphereFadePatches` (no `android.*`) turns line and control rectangles into patch rectangles with the rule book's padding rule. `SphereFadeLayout` measures its children's text lines and controls, asks for the patches, draws them in the background colour, blurred, at the fade strength, on a software layer so the blur works on API 26, and re-measures on layout changes.
- **Verification:** `DotSphereModelTest` checks the dot count for both width classes, that every dot lies within the expected radius band, that placement is the same for the same seed, that `advance` changes nothing when motion is off, that a ripple decays to zero, that `project()` returns dots back to front with alpha inside the floor and cap, and that a touch moves the centre toward it. `SphereFadePatchesTest` checks the padding at both clamp limits and that an empty line gives no patch.

#### Step 6: Layouts
- **Status:** todo
- **Depends on:** 3, 5
- **Files:** `app/src/main/res/layout/activity_main.xml` (modify: rewrite), `app/src/main/res/layout-w600dp/activity_main.xml`, `app/src/main/res/layout-w1024dp/activity_main.xml` (create), `app/src/main/res/layout/page_server.xml`, `page_backend.xml`, `page_tokens.xml`, `row_authorized_app.xml`, `sheet_confirm.xml`, `view_explain_card.xml`, `view_notice.xml` (create), `app/src/main/res/menu/bottom_nav_menu.xml` (modify: icons, titles from strings), `app/src/main/res/values/strings.xml` (modify: the labels these layouts need), `MainActivity.kt` (modify: `onCreate`, only to drop the two lines for the removed dead views)
- **Change:**
  - Each frame holds the sphere view, the content panel and the navigation for its width: `BottomNavigationView` with the existing id `bottom_navigation` in `layout`; `NavigationRailView` with the new id `nav_rail` in `layout-w600dp`, icons only, each with its title as spoken name and tip; `NavigationView` with the new id `nav_panel` in `layout-w1024dp`. Each frame has exactly one of the three.
  - The top bar keeps the id `toolbar` in every frame and is part of the content panel's surface, so no title sits bare on the dots. In the 1024dp frame it is the content header: title on the left, status light and word on the right, tick rule beneath.
  - In the phone frame the content panel runs edge to edge with square corners and no side border; the wider frames use the panel corners. Content is capped at the measure and centred; the settings pages use the settings measure.
  - Keep every existing id and widget class that `MainActivity` and `scripts/device.sh` use. The two dead views `ll_pending_requests` and `tv_pending_label` go, with their two lines in `onCreate`.
  - Server: an open top block inside `SphereFadeLayout` (status light and word, the working dots beside them, headline in the greeting style at its smallest size, address in the main text colour). Then, on the panel: an error notice strip slot and an explain card slot; sections "Model" (select model, the model path on one line cut with an ellipsis, a progress bar slot) and "Server" (the switch) as standard rows; then a fold-open "Diagnostics" box, shut by default, whose header row has the new id `fold_diagnostics` so `scripts/device.sh tap` can open it, holding the six test buttons, a second explain card slot for the microphone and the logs code box.
  - Backend and Tokens: reading columns of sections with typewriter labels, tick rules and rows; no grouping cards. The four pick-one settings are side-by-side segments, each on its own full-width line on a phone. The context size uses the text-box style with label above, marked optional, and hint below. A small-print line under back up says the file holds the token and must be kept secret (`security.md`). One plain line for "no authorised apps yet". `row_authorized_app.xml` has its own ids for the package name and the detail line.
  - A reason line sits under every control the policy can switch off.
  - The label style is used only for section names, figures, times and status words. Titles and headings are in ink, not blue; blue appears only on things that can be pressed or are selected. No text container has a fixed height, so text at twice its size wraps and nothing is clipped.
  - Button kinds: Quiet for select model and generate token; Outline for the tests, copy token, back up and restore; Plain for delete token (it opens the confirm sheet) and Cancel; Danger only inside confirm sheets; no page has a Main button, because the Server page's one action is the switch.
  - No literal text and no raw value in any layout.
- **Verification:** `./gradlew lintDebug` passes; the Approach's raw-value check passes on `res/layout*`, and no `android:text="` there is followed by anything but `@string/`. On the emulator at phone width, after visiting each page and opening the Diagnostics box, every id that `MainActivity` looks up and every id named in `docs/testing-on-device.md` and `scripts/device.sh` appears in `scripts/device.sh ui`. (The wide frames are launched from step 8 on.)

#### Step 7: Activity state and `render()`
- **Status:** todo
- **Depends on:** 4, 6
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ControlsPolicy.kt`, `ContextSizeInput.kt` and `ConsoleUi.kt` (create; `ConsoleUi` starts with the brief message only), `ControlsPolicyTest.kt` and `ContextSizeInputTest.kt` in `app/src/test/java/com/aanand/edgeaicore/` (create), `MainActivity.kt` (modify: `onCreate`, `onResume`, `statusReceiver`, the switch listener, `updateUIForStatus` (replaced by `render`), `updateTokenUI`, `getOrGenerateToken`, `generateNewToken`, `performBackup`, `performRestore`, `startInferenceService`, `stopInferenceService`, `copyModelFile`, `startAudioRecording`, `stopAudioRecording`, `runTestAudio`, `runTestVision`, `runTestInference`, `runTestMultiTurn`, the health and load listeners)
- **Change:**
  - `ControlsPolicy.kt` (no `android.*`): the state and result data classes and `compute`, exactly as the Approach's rules say. `ContextSizeInput.parse` (no `android.*`): empty is the model's default, otherwise a whole number above 0, anything else is invalid; `startInferenceService` uses it in place of its own parsing.
  - `MainActivity` gets the state fields (pending action, busy set) and `render()`, the only code that sets `isEnabled`, `isChecked`, the status light, word and detail, reason lines, busy labels and spinners, the working dots, the sphere's working state, the error strip and its action. Remove every direct write listed under Current behaviour: the functions named above stop setting `tvStatus`, `isEnabled` and `isChecked`, change the state instead, and call `render()`.
  - The switch listener reads what the person chose, then: for on, checks the context size with `ContextSizeInput` and either shows the reason under the box or sets the pending start and calls `startInferenceService`; for off, sets the pending stop and calls `stopInferenceService`. A start or stop call that throws clears the pending action, logs it and shows a brief message. `statusReceiver` and `onResume` clear the pending action and call `render()`. The error strip's action runs the same code as switching on, or opens the model picker, as the policy says.
  - Each busy task is added when its work starts and removed in a `finally` on the main thread: the six tests, back up, restore, and the model copy. The audio test's busy state spans the recording and the request, so it is removed at every exit: the permission check's early return and the `catch` in `startAudioRecording` (both also reset `isRecording`), a new `try`/`catch` around the recording coroutine's body (which rethrows `CancellationException`, logs any other failure, releases the recorder and resets `isRecording`, so a recorder that cannot start no longer crashes the app), the early return in `runTestAudio` and its `finally`. `runTestVision`'s early return when the image cannot be read is covered by its `finally` too.
  - The model copy reports progress to a progress bar in the Model section: a known amount when the provider gives the file size, otherwise a moving bar; `copyModelFile` reads the size, copies in blocks and posts progress a few times a second.
- **Verification:** `ContextSizeInputTest` covers empty, a valid number, zero, a negative number and text. `ControlsPolicyTest` covers each rule of the Approach: every kind with and without a model; both pending actions; a busy test, copy, backup and restore; with and without a token; the error strip's two actions. `grep -nE 'isEnabled *=|isChecked *=|tvStatus\.text' MainActivity.kt` matches only inside `render()`. On the emulator: with the server ready, start the vision test and, while it runs, its button stays busy after returning from the picker; stop the server mid-test and the button returns from busy; pick a model while the server is off and the progress bar runs and the switch becomes usable when the copy ends; copy token and delete token show their reason with no token; a start with the model file deleted shows the error strip, and "Try again" starts again.

#### Step 8: Activity wiring and behaviours
- **Status:** todo
- **Depends on:** 7
- **Files:** `MainActivity.kt` (modify: `onCreate`, `onResume`, `onPause`, `onSaveInstanceState` (new), `dispatchTouchEvent` (new), `setupTokenListeners`, `generateNewToken`, `performBackup`, `performRestore`, `updateAuthorizedAppsUI`, `checkAndRequestPermissions` and `requestIgnoreBatteryOptimizations` (removed), `startInferenceService`, the audio test listener, `requestPermissionLauncher`, `requestMultiplePermissionsLauncher` (removed), `render`, companion), `app/src/main/java/com/aanand/edgeaicore/ConsoleUi.kt` (modify: add the confirm sheet and the explain card binding), `ExplainCardPolicy.kt` (create) with `ExplainCardPolicyTest.kt` in `app/src/test/java/com/aanand/edgeaicore/` (create), `app/src/main/AndroidManifest.xml` (modify: `windowSoftInputMode` on the activity)
- **Change:**
  - Edge to edge with `WindowCompat.setDecorFitsSystemWindows(window, false)`; system-bar and keyboard insets become padding on the bars and the scrolling page; bar icon colours follow day and night in code.
  - Navigation: the `bottomNav` field becomes nullable, with nullable fields for `nav_rail` and `nav_panel`; only the one present gets a listener (`setOnItemSelectedListener` for the bar and rail, `setNavigationItemSelectedListener` for the panel). `showPage(itemId)` sets page visibility, the top bar title, the selected item and the sphere strength; the Server page's scroll past the open-until-scroll distance switches the sphere to reading strength. The selected page and whether the Diagnostics box is open are saved in `onSaveInstanceState` and restored.
  - Back returns to Server from the other pages and is disabled on Server. Sheets are `BottomSheetDialog`s, so Back closes an open sheet first.
  - Glass or solid is chosen once in `onCreate` (API level, or the debug-only launch extra) and applied to the panel backgrounds and frost views.
  - Sphere: `onResume` and `onPause` start and stop it; `dispatchTouchEvent` passes each touch position to it and never consumes the event; it ripples when the server is switched on and when a token is generated; `render()` sets its working state.
  - Confirm sheets for delete token and revoke access, each with a plain Cancel on the left and the red, named action on the right; the generate confirm is removed. Every `Toast` becomes a brief message, anchored above the bottom bar where there is one.
  - Authorised-app rows inflate `row_authorized_app.xml`, and `updateAuthorizedAppsUI` looks up that layout's ids instead of `android.R.id.text1` and `text2`; the row shows the package name and a revoke action and no part of the token; the empty line shows when there are none.
  - Settings are written to the existing preference keys when a choice changes or the context size box loses focus, and the backend choice is written under a new key and restored in `onCreate`. Invalid context input shows the reason under the box and is not saved. `ContextSizeInput.parse` decides validity here as it does for the switch. A status word shows "Saving" while the write is made and "Saved" when it is done, and while the stored kind is starting or ready it reads "Saved. Applies the next time the server starts" (Decisions 4). `startInferenceService` still reads the controls.
  - Explain cards follow the Approach's rules exactly. `ExplainCardPolicy` (plain Kotlin) takes the stored kind, whether each permission is missing, each answered flag and whether a request is awaiting its result, and returns the notifications card, the battery card or none; `render()` shows it. Answered flags are new keys in the existing `litertlm_prefs` file. The notification request uses a permission launcher and the battery screen an activity-result launcher, so each has a result callback. The audio test listener shows the microphone card when the permission is missing; its allow button uses the existing `requestPermissionLauncher`, whose grant branch already starts the recording and whose denied branch now hides the card and shows the brief message. `checkAndRequestPermissions`, `requestIgnoreBatteryOptimizations` and `requestMultiplePermissionsLauncher` go.
- **Verification:** `ExplainCardPolicyTest` covers each branch. On the emulator: Back from Backend lands on Server and Back on Server leaves the app; `adb shell input keyevent KEYCODE_ESCAPE` closes an open confirm sheet and then returns from Backend to Server (Android maps an unhandled Escape to Back; if that does not hold, add a key handler that does the same); the keyboard does not cover the context size box; delete and revoke show a sheet and generate does not; the three wide and narrow frames each start and switch pages. After `adb shell pm revoke` of the microphone and notification permissions and removing the battery whitelist: no system prompt and no card appear at launch; the server starts and reaches ready with no card in the way; the notifications card appears once it is ready; "Not now" hides a card and it stays hidden after a restart; allow opens the system screen and the card is gone on return whatever the answer; the audio test starts after the microphone is allowed; with the microphone denied twice, allow hides the card and shows the message. The Approach's raw-value check passes on the new Kotlin files.

#### Step 9: Words
- **Status:** todo
- **Depends on:** 8
- **Files:** `app/src/main/res/values/strings.xml` (modify), `MainActivity.kt` (modify: literals in `appendLog` calls, titles, sheet text, `performBackup` and `performRestore` log tags, `generateNewToken`), `InferenceService.kt` (modify only if a literal user-visible string remains)
- **Change:** Rewrite every user-visible string to Part 9: sentence case; buttons as a verb and a thing ("Select model", "Generate token", "Back up tokens", "Check health"); statuses as "Off", "Starting", "Ready", "Couldn't start"; errors as what happened, then what to do; no "successfully"; real ellipsis characters; durations and numbers in the rule book's formats; no emoji or symbol characters. Move every remaining literal shown to the user (log lines, titles, sheet text) to `strings.xml` with format arguments; remove strings nothing uses any more; use one companion constant for the server address. The generated-token line drops its `take(8)` prefix; `performBackup` and `performRestore` log with the `TAG` constant. Keep the notification channel id; reword the notification title and text.
- **Verification:** `./gradlew lintDebug` reports no `HardcodedText` or `SetTextI18n` for `MainActivity.kt` and the layouts; `grep -n 'appendLog("' MainActivity.kt` and a grep for non-ASCII symbols in `strings.xml` (other than the ellipsis) return nothing.

#### Step 10: Docs, scripts and the device check
- **Status:** todo
- **Depends on:** 9
- **Files:** `README.md`, `docs/standards/android.md`, `docs/standards/security.md`, `docs/standards/errors-and-logging.md`, `docs/standards/testing.md`, `docs/architecture.md`, `docs/pitfalls.md`, `docs/testing-on-device.md`, `docs/release.md` (modify), `scripts/device.sh` (modify only if an id or a grepped log line changed), `app/lint-baseline.xml` (modify: regenerate so fixed entries drop out)
- **Change:**
  - Docs. README usage guide: the new tab names, labels and permission flow. `android.md`: the UI section names the design system, the two value files and their check, the three width frames, the store and `render()`; the Permissions section describes the explain cards. `security.md` and `errors-and-logging.md`: the lines naming `checkAndRequestPermissions` and toasts. `testing.md`: the new test classes, and the five UI classes in the agreed exception (Decisions 1). `architecture.md`: the new classes. `pitfalls.md`: mark 15 and 23 `*(Removed.)*` keeping their numbers, and update 24. `release.md`: the diagnostics button names. `testing-on-device.md`: the install row and UI-flow example (the Diagnostics box must be opened before a test button can be tapped, the status word to poll for), the commands used below, and the second dedicated emulator `claude_edgeaicore_api26` (Decisions 2) in place of the "use only" sentence.
  - Regenerate the lint baseline and confirm it only shrank.
  - Device check on `claude_edgeaicore_api34`: the full flow (`scripts/device.sh up`, the six tests, token generate, copy, delete, back up, restore, revoke), then screenshots of the three pages for day and night; phone width, a 600 to 1023dp width and a 1024dp or wider width (display size and density overrides); glass and forced solid; font scale 2.0; keyboard focus on a button, a row and the switch. With animations off, start the server and take two screenshots a second apart during the load: they must be identical. Measure decode speed with the app in the foreground (sphere running) and in the background.
  - Device check on `claude_edgeaicore_api26` (created for this, UI only, no model): the app starts, runs in solid mode, and the three pages pass the checklist items that need no running server, day and night.
  - Walk the Part 10 checklist per screen and record each item as pass or fail in the ticket's Verification section; evidence goes in the ticket's evidence folder.
- **Verification:** `scripts/check_docs.sh` passes; `git diff --stat app/lint-baseline.xml` shows deletions only; `./gradlew testDebugUnitTest lintDebug` pass on the final commit; every checklist item is recorded as pass.

### Tests
- New unit tests: `ConsoleTokensTest` (token parity, the three curves, contrast, sphere formula numbers), `ServerStatusStoreTest`, `ControlsPolicyTest`, `ExplainCardPolicyTest`, `ContextSizeInputTest`, `DotSphereModelTest`, `SphereFadePatchesTest`.
- `DotSphereView`, `SphereFrostView`, `SphereFadeLayout`, `TickRuleDrawable` and `ConsoleUi` only draw or show UI and cannot run in a JVM unit test; their logic is in the tested plain classes, and the owner agreed (Decisions 1) that these five have no test class.
- Existing tests must stay green. Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`, then the device checks of step 10.

### Risks and open questions
- **Blurred sphere copy is unproven.** If `RenderEffect` on the frost views costs too much or looks wrong, use solid on all API levels and record it; the rule book permits solid.
- **A start reaching a stopped but not yet destroyed service instance** is handled by the store either way, but which of the two Android does was not confirmed.
- **Solid mode is checked on an API 26 emulator, not on a real pre-API 31 phone.** Lint `NewApi` guards the API-level branches.
- **Sphere cost during inference.** If decode speed drops measurably with the app in the foreground, lower the frame rate while the kind is ready and record the numbers.
- **Material widgets and per-corner shapes.** If a widget ignores the shape appearance (the switch track, a navigation indicator), give it a drawable background.
- **Legibility over the translucent panel.** If text fails 4.5 to 1 against a dot on the device, raise that surface to its solid value and record it.
- **Font licences** must be confirmed from the downloaded files; stop if one is not OFL.
- **A second start sent while a load is running** (only possible from adb) is skipped by the service as today; the first load's result is still shown.
- **Picking a model while the server is starting or ready** copies over the file by name, as today; if it is the model in use, the running engine's file is replaced under it. Left as it is; a follow-up could allow the picker only while the server is off.
- **Large ticket.** Steps are ordered so the app builds and runs after each one; commit per step.

### Decisions for the owner
1. **New UI classes without a test class.** `docs/standards/testing.md` rule 1: "every new or changed source class has a test class", with the "Agreed exception: Android framework classes (`InferenceService`, `MainActivity`) and `LlmEngine.kt`". The plan adds five classes that only draw or show UI (`DotSphereView`, `SphereFrostView`, `SphereFadeLayout`, `TickRuleDrawable`, `ConsoleUi`); JVM unit tests cannot call `android.*`. Options: (a) extend the agreed exception to these classes, their logic being in tested plain classes (recommended); (b) add Robolectric, a new test dependency; (c) add instrumented tests under `app/src/androidTest`, which need the emulator for every run.
   - **Decided 2026-11-25:** (a). The exception is extended to these five classes.
2. **Checking solid mode below API 31.** `docs/testing-on-device.md`: "Use only the dedicated `claude_edgeaicore_api34` emulator for automated runs". The acceptance criterion asks for solid mode below API 31 to be checked. Options: (a) create a second dedicated emulator at API 26 for UI-only checks and document it (recommended); (b) accept forced solid mode on API 34 plus lint `NewApi` as the check.
   - **Decided 2026-11-25:** (a). A second dedicated emulator at API 26 is created for UI-only checks.
3. **Android elevation numbers in the design system.** Rule book 8.5: "A new colour, typeface, corner size or shadow needs Archit's say-so". Android cannot draw the two CSS shadows as written; it needs an elevation in dp for resting and lifted. Options: (a) add the two elevation numbers, chosen on the emulator to match the reference page and recorded in the change log (recommended); (b) no shadows on Android, pieces are told apart by their edges only.
   - **Decided 2026-11-25:** (a). The two elevation numbers are added to the design system.
4. **Settings changed while the server is running.** The Description says "Settings apply at once" (rule book 7.7). The backend, context size, speculative decoding and encoder settings are read only when the server starts and the model loads, so a change cannot take effect on a running server without reloading the model. Options: (a) save at once and say "Saved. Applies the next time the server starts" while it is running (recommended); (b) restart the server automatically on each change, with the whole-screen wait.
   - **Decided 2026-11-25:** (a). Settings are saved at once; a running server picks them up at its next start, and the page says so.

### Success criteria
- [ ] Every acceptance criterion of this ticket has a recorded pass in the Verification section.
- [ ] `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass; the lint baseline only shrank.
- [ ] No `md_theme_*` name, no literal UI text in Kotlin or layouts, and no raw value outside the token and piece value files.
- [ ] Control state is set only in `render()`; the service's status is stored only through `ServerStatusStore`.
- [ ] `scripts/device.sh` flows work unchanged, or the script and `docs/testing-on-device.md` were updated together.

### Unresolved issues
None blocking. Logged, not changed:
- A model picked while the server is starting or ready is copied over the file in use, as today (see Risks). Suggested follow-up: allow the picker only while the server is off.
- The busy set is lost when the activity is recreated, as today.
- Whether Android delivers a start to a stopped but not yet destroyed service instance was not confirmed; the store is written to be correct either way.

### Reviewer brief
- **Objective:** make every screen of Edge AI Core pass the Part 10 checklist of the Console at Rest rule book v1.2, without changing inference, the REST API or token logic.
- **Key decisions:** Views, no Compose, no new libraries. Shared values mirror `tokens.json` and are guarded by a parity test; a piece's own geometry and movement numbers are copied from the reference page into one `pieces.xml`; nothing else holds a raw value. Three width frames (bottom bar, rail, navigation panel) share three page layouts. The dot sphere is a plain Kotlin model drawn by views; frosting on API 31 and later is a blurred copy of the sphere, solid panels below. Server is the open home screen; Backend and Tokens are reading columns with side-by-side pick-one settings built from the existing `RadioGroup`s. `ServerStatusStore` is the one owner of the server status and accepts reports only from the current service instance. All control state in the activity is computed by `ControlsPolicy` and applied only in `render()`. Explain cards are inline, show only while the server is ready, and never block an action. Settings are saved at once and apply at the next server start. Five UI-only classes have no test class (owner's decision). Solid mode is checked on a second dedicated emulator at API 26. Two Android elevation numbers are added to the design system.
- **Out of scope:** REST API, inference, token and conversation storage, `targetSdk`, dependency versions, the notification's look, the launcher icon, moving the HTTP test client out of `MainActivity`, lifting the Android pieces into the design-system repo, removing the unused permission declarations.
- **Success criteria:** every acceptance criterion has a recorded pass; `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass and the lint baseline only shrank; no `md_theme_*` name, no literal UI text in Kotlin or layouts, no raw value outside the token files; `scripts/device.sh` flows still work.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
Path: pair (two Haiku validators per round: requirement and standards; failure scenarios). Every issue was checked against the code before it was accepted.
- **Round 1.** Both FAIL; judged FAIL. Valid blocking: new UI classes without test classes (logic moved to tested plain classes; the rest became Decision 1); step 9 missed the Permissions section of `android.md` (added); `bottomNav` typed `BottomNavigationView` would crash the wide frames (nullable fields, one per frame); an explain sheet dismissed by Back could leave the switch disabled (rule added). Valid minors fixed: status kind per call site, verification that visits pages and opens the box, busy state cleared in `finally`, a raw-literal check, lowercase font file names, the three curves, pitfall entries, dot counts, long paths. Decision 2 (API 26 emulator) recorded.
- **Round 2.** Both FAIL; judged FAIL. Valid blocking: step 2 removed `md_theme_*` before step 3 rewrote the themes (names kept until step 3); a system permission screen opened in the server-start path would make the activity miss status broadcasts. Second blocking issue on the permission flow, so it was redesigned: inline explain cards decided by `ExplainCardPolicy`, never part of an action. Minors fixed: frost views share the sphere model, contrast test worst case, doc examples, saved state. Disproved: "allow on the microphone card starts nothing" (the existing `requestPermissionLauncher` grant branch starts the recording). Decision 3 (elevation numbers) recorded.
- **Round 3 (full).** Both FAIL; judged FAIL. Valid blocking: reason lines and busy states missed copy token, delete token, backup, restore and model copy (added); step 2's mapping omitted glass, sphere and words-on-sphere values (added). Valid, taken as a simplification of the card rules: a card whose allow button does nothing after Android stops prompting (any answer now hides the card). Changed: pick-one settings become side-by-side segments (rule book 7.3); cards show only while the server is ready (point of use). Disproved: empty list needs the dead-end layout (rule book 2.7: never beside real content); phone bottom bar deviates (the reference page uses `pop`); media permission removal is outside the requirement (it follows from point-of-use requests).
- **Round 4 (full).** Both FAIL; judged FAIL. Valid blocking: the card gate depended on the activity having seen "ready" while the missed-broadcast pitfall was out of scope. Redesign: the service owns the status and the activity reads it on every broadcast and in `onResume` (pitfall 23 brought into scope). Minors fixed: notifications card only on API 33 and later, audio test busy paths, battery launch that throws, phone panel edge to edge, pop-up title and greeting styles, Cancel on confirm sheets, logging and token-prefix standards in touched functions, backup secrecy hint, elevation numbers in `tokens.css` first.
- **Round 5 (full, cap).** Both FAIL; judged FAIL. Valid blocking: a load finishing after a stop could store "ready" over "off"; stop then start could mix statuses of two runs (a `stopped` flag was added). Minors fixed: source of the sphere's numbers, Escape check, curve assertion moved to step 3, vector drawable exemption, stars dimming, wide header, working dots, `CancellationException` rethrow.
- **Owner decisions (2026-11-25).** 1: extend the test exception. 2: second emulator at API 26. 3: add elevation numbers. The owner also approved one extra validation round.
- **Post-decision round 1 (full).** Both FAIL; judged FAIL. Valid blocking: the `stopped` flag let `onDestroy` overwrite an error with "off" and did not survive every stop and start ordering (third blocking issue on the status mechanism: redesigned as `ServerStatusStore` with one lock); elevation numbers written in step 1 but chosen in step 3 (step 1 picks starting numbers, step 3 may adjust both sides); values with no token source (step 1 now adds them to the design system); "settings apply at once" against saving for the next start (Decision 4, decided: save now, apply at next start). Accepted without change: unused permission declarations stay.
- **Post-decision round 2 (full, last allowed).** Both FAIL; judged FAIL. Valid blocking: the stop-count store could stick at "starting" if a start reached the same instance while its old load ran, and other code paths wrote the status line directly; reduced motion covered only the sphere. Fixed in the text (store keyed on the reporting instance, other writers routed through the store, a rule for all looping movements) but not re-validated: see Unresolved issues. Minors fixed: remaining sphere numbers, elevation verification, emoji in log lines, model tests, top bar surface, off-state rules, test isolation. Disproved: the five UI classes are "private pieces" under rule book 8.5 (they implement catalogued pieces; the design-system README lifts components when a second app needs them).

- **Added round (owner asked for it, full).** Both FAIL; judged FAIL. Valid blocking: `onResume` re-enabled a busy test button, because several code paths set control state; the piece numbers (movements, switch geometry, spinner) had no stated source; hovered and error states were missing from the styles. Minors valid: the "loaded" log line written for a dropped publish, an undefined "try again", `docs/release.md`, rail icons without spoken names, no progress bar for the model copy, other writers of the switch state.
- **Rewrite (2026-11-25).** After that round the whole plan was rewritten from a full read of `MainActivity.kt` and `InferenceService.kt` instead of being patched again. What changed: all control state is decided by a tested plain function and applied in one `render()`; numbers are split into token values (checked against `tokens.json`) and piece values copied from the reference page (assumption 1), so step 1 is only the shadows; the full set of states and movements is in step 3; the model copy gets a progress bar; `ContextSizeInput` moved to step 7; step 10 now holds the docs and both device checks.
- **Round on the rewritten plan (full).** Failure scenarios: PASS, five minors. Requirement and standards: FAIL with two issues it called blocking; judged PASS. Both were one-sentence gaps whose answer the requirement already fixes, and neither makes the plan unexecutable or breaks behaviour or a repo rule: the touch size could be read as the token's 44 off phones (now 48 at every width, as the acceptance criterion and rule book Part 6 say); "Saving" was missing before "Saved" (added). Minors fixed mechanically: the second token prefix in the authorised-app row, create markers, the store in its own file (`kotlin.md`), where the resting elevation applies, the address line's colour, "optional" on the context size, the audio test's remaining exits, no switch reason while starting, the brief message helper created in step 7. Logged without change: a model picked while the server runs overwrites the file in use (existing behaviour); the busy set is lost on recreation (existing behaviour).
## Implementation notes
### Autopilot run (2026-11-25)
- Mode: automatic
- Checkout: /home/archit-anand/Coding/repos/android_edge_ai_core (design-system repo: /home/archit-anand/Coding/repos/console-at-rest)
- Branch: feature/console-at-rest-design-system  PR base: develop  Starting commit: a26f8674a47ccbf08f15c3a65da91c42fa3f066e
- Dirty before start: none
- Work files: app resources and layouts, 13 new Kotlin classes, MainActivity, InferenceService, manifest, build.gradle.kts, 7 new test classes, docs; console-at-rest: tokens.json, tokens.css, RULEBOOK.md
- Counters: fix 3/10, review 1/5, plan amendments 0/3, re-verify 0
- Last completed stage: 5 review iteration 1 (resolved)  Next: 5 review round 2, then re-verify (runtime changed)
- Hard blocker: none
- WIP commit: none

### Autopilot decisions (2026-11-25)
- Pre-flight: the request "work on this ticket till it's done" names ticket 25 from the conversation, so the run is in ticket mode for ANDROIDEDGEAICORE-25.
- Pre-flight: stage 1 skipped; the ticket's Plan is already `Validated - minors logged` with all owner decisions taken.
- Pre-flight: used the checkout at ~/Coding/repos (on `develop` at the latest origin commit) rather than the older one under docker-data.
- Pre-flight: branch `feature/console-at-rest-design-system` from `develop` (gitflow: `feature/<topic>`, lowercase kebab).
- Stage 2: downloaded the seven font files and their OFL texts from the official repos (sora-xor/sora-font, Instrument/instrument-sans, JetBrains/JetBrainsMono v2.304), as the approved plan's step 2 says; all three are OFL 1.1.
- Stage 2: the console-at-rest change is committed and its PR opened in stage 6 with the app PR (not in step 1), so the emulator comparison can still adjust the elevation numbers.
- Stage 2: the contrast test models the blur over each translucent surface (a raw dot under 5% night glass fails even for ink at 2.6:1); status words on the open sphere use ink and the light carries the colour.
- Stage 2: touches only lean the sphere; ripples are for commits (rule book 3.1), not every press as in the reference script.
- Stage 2: 'Saved' is the settings status word, with 'Applies the next time the server starts.' as a small line beneath it.
- Stage 2: the step 10 device check and Part 10 checklist walk run in the verify stage.

### Baseline (2026-11-25)
- `./gradlew testDebugUnitTest lintDebug`: 220 tests, 0 failures. Lint passes; 2 warnings not in the baseline already existed (AndroidGradlePluginVersion in gradle-wrapper.properties, GradleDependency in build.gradle.kts) plus a LintBaselineFixed hint.
- `scripts/check_docs.sh`: OK (version 2.0.0).

### Implementation progress
- Branch: feature/console-at-rest-design-system (design-system repo: feature/android-shadows)
- [x] Step 1: Add the shadows to the design system - console-at-rest: tokens.json (shadow block with day/night rest and lift strings, androidElevation rest 3, lift 8), tokens.css (--elevation-rest 3, --elevation-lift 8), RULEBOOK.md 2.5 sentence and change-log line. Deviation: the commit and PR in that repo are made in stage 6 with the app PR, so step 3 can still adjust the numbers.
- [x] Step 2: Value resources, fonts and the parity test - res/values/colors.xml, values-night/colors.xml, values/tokens_dimens.xml, values/tokens_numbers.xml, values-night/tokens_numbers.xml, values/pieces.xml, values-night/pieces.xml, res/font/*.ttf (7, 984 KB), assets/licenses/ (3 OFL texts + README with sources), test resources console-at-rest/tokens.json, ConsoleTokensTest. Deviations: (a) app/build.gradle.kts declares src/main/res as a unit-test input, otherwise Gradle skipped the test as up to date after a resource change and drift went unnoticed; (b) the contrast worst case cuts a dot's strength by the blur of the surface over it ((dot radius / (dot radius + blur))^2) and by the dot alpha cap: with a raw full dot under a 5% night glass, even the ink colour is 2.6:1, which no surface could pass; (c) status words on the open sphere are in ink, not ok/warn/bad colours (ok on the day fade is 4.28:1), the status light carries the colour; (d) sphere alpha cap and stars are night-qualified piece values.
- [x] Step 3: Theme, styles, shapes, states, movements and icons - values/themes.xml (every M3 colour role, shape and text appearance mapped to tokens; transparent system bars; ThemeOverlay.Console.Solid), values-night/themes.xml (deleted: colours are night-qualified, so one theme serves both), values/attrs.xml (consoleGlass, consoleGlassOpen, consoleGlassStrong, consolePop), values/type.xml, values/shapes.xml, values/styles.xml (Main, Quiet, Outline, Plain, Danger buttons; switch; segment track and segment; text box; row; top bar; bottom bar; rail; navigation panel; fold; code box; notice; explain card; brief message; bottom sheet; progress), color/ (22 state lists, switched off at 45%), drawable/ (focus rings drawn 2 outside the piece by a negative inset, segment, text box with focus halo and error via the activated state, row, fold, code box, notice, card, brief, panels, bottom bar, switch track and thumb, status light level-list, working dot, tick_rule.xml, three nav icons and the chevron), interpolator/ (three curves), animator/ (button_lift, msg_in, pop_over_in, fade_scale), TickRuleDrawable.kt (inflated from XML through the drawable class attribute), md_theme_* colours removed, ConsoleTokensTest asserts the curves and the line-height products. Deviations: fonts are referenced per weight file (no font-family XML), because API 26 and 27 cannot pick a 500 weight from a family; line heights are piece dimens (size times factor) checked by the test; the elevation numbers stay at 3 and 8 until the step 10 emulator comparison.
- [x] Step 4: Status store and service reporting - ServerStatus.kt, ServerStatusStore.kt, ServerStatusStoreTest.kt (8 tests), InferenceService.kt (serviceStatus removed; begin/stop/end wired; sendStatusBroadcast(kind, text) publishes then broadcasts; broadcastStatus(); 'no model to load' error; fixed English LOG_MODEL_LOADED line logged only when stored), MainActivity.kt (statusReceiver and onResume read ServerStatusStore.current; updateUIForStatus switches on the kind), strings.xml (status_no_model). Unit tests 236/236 pass. The step's emulator scenarios run in the verify stage.
- [x] Step 5: The dot sphere - DotSphereModel.kt (with DotSphereSpec and ProjectedDot), DotSphereModelTest.kt (8 tests), DotSphereView.kt, SphereFrostView.kt, SphereFadeLayout.kt, SphereFadePatches.kt, SphereFadePatchesTest.kt (5 tests); tokens_numbers.xml gains breakpoint_tablet 600 and breakpoint_computer 1024 (checked by the test); pieces.xml gains sphere_script_frame_ms and sphere_strength_change_ms (the reference .layer opacity transition, 1.2 s). Deviations: the script's per-frame coefficients are scaled to the real frame time (decay^(dt/16.667), lerp 1-(1-k)^frames) so speed does not depend on the display rate; the change between open and reading strength is a ValueAnimator of the reference's 1.2 s, so it follows the system animator scale; stars use the night ink colour (the script's #FFFFFF has no token).
- [x] Step 6: Layouts - layout/activity_main.xml (phone frame: sphere, top bar, pages, bottom bar, brief host), layout-w600dp/activity_main.xml (rail), layout-w1024dp/activity_main.xml (navigation panel and content header with status light and word and tick rule), page_server.xml (open block in SphereFadeLayout: status light, word, working dots, headline, address, tv_status; reading panel: notice and explain card slots, Model and Server sections, Diagnostics fold with id fold_diagnostics holding the six tests, the microphone card and the logs code box), page_backend.xml (settings measure, four side-by-side pick-ones on their own lines, context size text box with Optional, label above, reason and hint below, settings status word), page_tokens.xml, row_authorized_app.xml, sheet_confirm.xml, view_explain_card.xml, view_notice.xml, menu with outline icons and string titles, values/frames.xml and values-w600dp/frames.xml (page_padding and page_panel aliases per width), strings for the new labels, MainActivity onCreate drops the two dead-view lines. Also SphereFrostView.onMeasure takes no room unless sized exactly (it made its wrap-content frame fill the screen) and the base button style sets a zero stroke (Material's default stroke showed on every button). Checked on the emulator at phone width: all three pages render (evidence step6-phone-*.png); the full id check runs after step 8 wires the fold.
- [x] Step 7: Activity state and render() - ControlsPolicy.kt (PendingAction, BusyTask, OffReason, ErrorAction, ControlsState, ControlState, Controls, ControlsPolicy.compute), ContextSizeInput.kt, ConsoleUi.kt (brief message, busy spinner on buttons, status light pulse, working dots), ControlsPolicyTest.kt (12 tests), ContextSizeInputTest.kt (3 tests), MainActivity.kt (state fields; render() is the only writer of enabled, checked, status light, word, detail, reasons, busy labels, working movements and the error strip; requestStart/requestStop with pending action and a brief message when the service cannot be reached; every busy task ends in a finally through endBusy; the audio test ends its busy state at every exit and the recording coroutine now catches failures, rethrows cancellation and always releases the recorder; the model copy reads the size, copies in 64 KB blocks and posts progress every 250 ms; getOrGenerateToken without !!; SERVER_ADDRESS companion constant; updateUIForStatus removed), page_server.xml drops tv_select_model_reason (select model is off only while it shows its own Copying… busy state), strings (status words, busy labels, try again, two messages). grep for isEnabled/isChecked/tvStatus.text writes: only inside render(). On the emulator the Qwen3-0.6B start shows STARTING with the working dots, then READY with the detail line (evidence step7-*.png).
- [x] Step 8: Activity wiring and behaviours - MainActivity.kt (solid mode chosen in onCreate from the API level or the debug-only EXTRA_FORCE_SOLID extra, applied as ThemeOverlay.Console.Solid; edge to edge with bar icon colours in code and nav-bar contrast off on API 29+; phone: top bar padded under the status bar with its minimum height raised to match, the bottom bar steps aside while the keyboard shows and the column is padded by the keyboard, the brief host sits above the bar; wide frames: frame_row padded by the bars and the keyboard and consumes the insets so the rail and panel do not pad twice; bottomNav, navRail, navPanel nullable, one listener each, showPage() sets visibility, title, checked item, Back callback and sphere strength and plays fadeScale; Server scroll past 120 dp drops the sphere to reading strength; page and fold state saved in onSaveInstanceState; fold opens with the chevron turning 90 degrees and a state description; Back returns to Server and is off on Server; frosts attached on API 31+ in glass mode; sphere start/stop in onResume/onPause, dispatchTouchEvent leans it toward touches, ripples on server start and token generate; delete token and revoke access confirm in bottom sheets, generate no longer asks; every Toast is a brief message; authorised-app rows from row_authorized_app.xml with no part of the token and the empty line; settings saved on change and on leaving the context box or pressing Done, backend under the new key 'backend'; explain cards per ExplainCardPolicy with answered flags in litertlm_prefs; microphone card from the audio test; checkAndRequestPermissions, requestIgnoreBatteryOptimizations and requestMultiplePermissionsLauncher removed; panels filled to the bottom by fillToBottom()), ConsoleUi.kt (confirm sheet, explain card binding, msgIn arrival), ExplainCardPolicy.kt with ExplainCardPolicyTest.kt (6 tests), AndroidManifest.xml (adjustResize), page layouts (panel frames wrap their content; the constrained height went stale when the fold opened), tv_settings_note on Backend, strings, lint fixes (InconsistentLayout and MergeRootFrame ignored on the frame-specific ids with the reason, baselineAligned off, the wide content column is a ConstraintLayout, unused resources removed, KTX helpers). Deviations: touches only lean the sphere, ripples are kept for commits as rule book 3.1 says (the script also ripples on every press); 'Saved' is the status word and 'Applies the next time the server starts.' is a small line beneath it, since a sentence does not fit the typewriter label; a return from the battery screen counts as an answer whatever it says (a granted exemption is no longer missing anyway). Lint: only the two warnings that were already there.
- [x] Step 9: Words - strings.xml rewritten to Part 9 (sentence case, verb-and-thing buttons such as 'Select model', 'Generate token', 'Back up tokens', 'Check health'; statuses Off, Starting, Ready, Couldn't start; errors say what happened then what to do; no 'successfully'; real ellipses; '5 s'; no emoji or symbols), every appendLog literal and the test prompts moved to strings with format arguments, the clipboard label and HTTP fallback text too, unused strings removed, the token prefix already gone, the notification title and text reworded (channel id kept), the Ping-failed log line in InferenceService kept as fixed English so docs/testing-on-device.md stays true. Checks: grep 'appendLog("' finds nothing; no non-ASCII in strings.xml other than the ellipsis; lint shows only the two warnings that were already there.
- [x] Step 10: Docs, scripts and the device check - README.md (tab and button names, permission flow, settings saved at once), docs/architecture.md (new classes), docs/standards/android.md (Permissions and UI sections: design system, the two value files and their test, three width frames, render() and the store), docs/standards/security.md, docs/standards/errors-and-logging.md (brief messages, literal tags fixed), docs/standards/testing.md (new test classes, the five UI classes in the agreed exception), docs/pitfalls.md (15 and 23 removed with their numbers kept, 21, 22 and 24 updated), docs/release.md (diagnostics names), docs/testing-on-device.md (second emulator claude_edgeaicore_api26, install and token rows, UI flow opens Diagnostics and polls tv_status_word for READY, the look commands), app/lint-baseline.xml (73 fixed entries deleted; deletions only, matched by id, message and file), appendLog uses TextView.append. scripts/device.sh unchanged (no tapped id or grepped log line changed). Deviation: the device check and the Part 10 checklist walk are run in the verify stage, with the API 26 emulator created there.

### Smoke test (2026-11-25)
- `./gradlew testDebugUnitTest`: 270 tests, 0 failures (baseline 220; new: ConsoleTokensTest 8, ServerStatusStoreTest 8, ControlsPolicyTest 12, ContextSizeInputTest 3, ExplainCardPolicyTest 6, DotSphereModelTest 8, SphereFadePatchesTest 5).
- `./gradlew lintDebug`: passes; only the 2 warnings that were already outside the baseline (AndroidGradlePluginVersion, GradleDependency). Baseline: 770 lines deleted, none added.
- `scripts/check_docs.sh`: OK.
- Raw-value check (colour, dp/sp/px, ms literals and bare alphas outside the token and piece files): clean.
- Emulator claude_edgeaicore_api34, phone width, day: Server, Backend and Tokens render; Diagnostics opens; Qwen3-0.6B start goes STARTING then READY (GPU falls back to CPU); evidence ANDROIDEDGEAICORE-25-evidence/step6-*, step7-*, step8-*.
- Not run yet (verify stage): wide frames, night, solid, API 26, font scale 2, keyboard, reduced motion, permission cards, sheets, the device.sh flows.

### Fix loop
- Round 1 (2026-11-25). Triage:
  - V5 product defect: DotSphereModel followed the last touch and never came back (the script's aim on pointerdown). Fix: DotSphereModel.release() returns the target to the middle and stops the lean; DotSphereView.release(); MainActivity.dispatchTouchEvent calls it on ACTION_UP/CANCEL. Regression test DotSphereModelTest.releasingATouchBringsTheSphereBackToTheMiddle.
  - V30/V17 product defect: the frosted sphere copies (RenderEffect blur behind the toolbar and every content panel) used a full core on the emulator and the sphere's 60 fps loop most of the rest; decode fell from 20.3 to 6.7 chunks/s and the streamed vision test passed 10 s to its first byte. Fix (the plan's own risk mitigations): frost only the floating navigation piece (rail, navigation panel); content panels keep the glass tint alone (rule book 2.1: their blur is 'almost none'); the sphere draws 10 times a second at rest and 30 for 2 s after a touch or while a ripple runs (pieces.xml sphere_frame_interval_ms, sphere_frame_interval_active_ms, sphere_active_after_touch_ms). Measured during the fix: 20 fps no panel frost 14.5, 10 fps 16.8-16.9 chunks/s. Content-panel SphereFrostViews removed from the layouts; the now single-child wrappers flattened (lint UselessParent); fillToBottom uses the panel's own top.
  - V8 product defect: Escape did nothing on Backend or Tokens. Fix: MainActivity.onKeyUp maps Escape to the Back-to-Server step when it is enabled (on Server Escape does nothing; it never leaves the app).
  - V11 product defect: no ring on bottom bar, rail and navigation panel items (they had none) or on segments (the track clipped it). Fix: nav_item_background.xml (ripple well + inner focus ring) for bar and rail, nav_panel_item_background.xml (selected tint + inner focus ring) for the panel; Widget.Console.SegmentTrack does not clip children; console_nav_fill.xml removed.
  - V25 wrong expectation: the plan expected READY after an adb stop then start; Android destroyed the instance after the start reached it (stopSelf from the stop), cancelling the load, as develop does too. The plan's Approach asks the store to show the true state, and it did (OFF). Superseded by V32. The service race is a pre-existing follow-up.
  - Docs finding: docs/testing-on-device.md now names system-images;android-26;default;x86_64.
- Round 1 checks: 271 unit tests pass, lint only the 2 warnings that were already there, check_docs OK, raw-value check clean.
- Round 1 re-run (on the round-1 build): V5, V8, V11, V16, V30 (17.2-17.5 vs 20.0 background), V17 API path PASS; Test vision from the UI still FAILED (unexpected end of stream): during the app's own tests the busy spinner, working dots and light pulse ran at the display rate and the RenderThread took 88% of a core, pushing the streamed image request past Ktor Netty's 10 s write timeout (pre-existing server limit, develop under the same 10 s).
- Round 2 (2026-11-25). Product defect (V17 UI). Fix: ConsoleUi drives the three looping movements from one Choreographer ticker at the sphere's rest interval (10 a second) instead of full-rate ObjectAnimators and Material's IndeterminateDrawable; the busy spinner is ConsoleUi.SpinnerDrawable (.spin: 14, stroke 2, three quarters of a ring); the keyframe maths is LoopFrames.kt (plain Kotlin) with LoopFramesTest (3 tests); MainActivity pauses and resumes the ticker in onPause/onResume. RenderThread during a test: 27% (was 88%). 274 unit tests pass, lint clean, check_docs OK, raw-value check clean.
- Round 3 (2026-11-25). Product defect (V8, landscape): the window did not draw into the display cutout. Fix: setUpEdgeToEdge sets layoutInDisplayCutoutMode SHORT_EDGES on API 28+; the existing insets (system bars plus cutout) keep the content clear. Re-run V8/V22: background fills the cutout strip, rail padded clear, portrait unchanged. 274 unit tests pass, lint clean.

## Verification
### Verification plan (2026-11-25)
**Plan:** this ticket's Plan  **Diff base:** a26f8674a47ccbf08f15c3a65da91c42fa3f066e  **Branch:** feature/console-at-rest-design-system (+ console-at-rest feature/android-shadows)

#### Scope
The whole UI move (resources, fonts, theme, styles, three width frames, three pages, sphere, frost, fade, status store, ControlsPolicy/render, explain cards, sheets, brief messages, settings saved at once, wording, docs, lint baseline) and the console-at-rest token change. Out of scope: REST contract, inference, token logic (only exercised as regression), notification look, launcher icon.

#### Environment and isolation
- Only the dedicated emulators: `claude_edgeaicore_api34` (`emulator-5584`, booted `-read-only -no-snapshot`, so every setting change and app datum is thrown away at shutdown) and a new `claude_edgeaicore_api26` (UI only, no model; `PORT=5586`, read-only). Never the owner's AVDs (`Pixel_9_Pro_XL`) or a phone.
- Models from `~/.cache/edgeaicore-test-models/` (Qwen3-0.6B for UI flows, Gemma 4 E2B for `device.sh up`, vision, audio and decode speed). Host port 18080; token saved by `device.sh` only in `~/.cache/edgeaicore-device/token`, never printed.
- Display, night mode, font scale, animator scale and permissions are changed with `adb shell` on the emulator only, and reset afterwards.
- Evidence and scripts: `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-25-evidence/` (`scripts/` for helpers).

#### Scenarios
| ID | Criterion | Scenario | Type | Method | Files | Expected result | Evidence |
|----|-----------|----------|------|--------|-------|-----------------|----------|
| V1 | AC1 | Raw-value check over layouts, styles, shapes, drawables, colour lists, animators, menus and new Kotlin; `ConsoleTokensTest`; mutate one colour and see it fail | unit/CLI | grep script, gradle | res/**, ConsoleTokensTest | no hits; test passes; mutation fails | command output |
| V2 | AC2 | Zoomed screenshots of buttons, text box, segments, switch, fold, notice, explain card, sheet, panels (wide), nav indicator | happy | emulator screenshot crops | styles.xml, shapes.xml, drawable/* | bottom-left corner squared; exceptions only circles, sheet top, edge-to-edge phone panel and bars | crops |
| V3 | AC3 | grep font references; screenshots: headings Sora, text Instrument Sans, labels/figures/status/code JetBrains Mono; labels only on section names, figures, status | happy | grep + screenshots | type.xml, layouts | only the 3 families; typewriter only on section names, status words, figures, code | grep output, screenshots |
| V4 | AC4 | Squint check of every page day and night; count Main buttons; grep emoji, gradient, glow | happy | screenshots + grep | layouts, styles | blue only on pressable/selected (selected nav, segments, switch on, Quiet buttons, status light ready/starting); 0 Main buttons; no gradients/emoji | screenshots, grep |
| V5 | AC5 | Sphere: Server top open; scrolled past 120 dp reading; Backend and Tokens reading; night stars; touch-and-hold leans; ripple on Run server and on Generate token; pulse while starting | happy | screenshot sequences, pixel diff | DotSphere*, MainActivity | strengths differ open vs reading; stars only at night; frames differ after touch, start, generate | screenshots, diff numbers |
| V6 | AC6 | Fade patch behind headline/status/address: page-colour patch where a dot passes behind a word, sphere bright beside | happy | screenshot + pixel sampling | SphereFadeLayout, SphereFadePatches | no visible patch edge; dots dimmed behind words only | crops, samples |
| V7 | AC7 | Three pages x day/night x phone (1080x2400 @420), tablet (wm size 1600x2560 @320 = 800 dp), computer (wm size 2560x1600 @240 = 1706 dp); switch pages in each | happy | wm size/density, cmd uimode | layout*, frames.xml, MainActivity.showPage | bottom bar / rail / navigation panel; header with status on computer; no crash | 18+ screenshots |
| V8 | AC8 | Edge to edge (content under status and gesture bar, kept clear); every pressable at least 48 dp (uiautomator bounds); delete and revoke sheets rise from the bottom; Back: Backend -> Server, Server -> leaves app; Back closes a sheet first; keyboard on context size box stays above the keyboard | happy/edge | screenshots, ui bounds, keyevents | MainActivity (insets, back), sheet_confirm | as stated | screenshots, bounds list, dumpsys |
| V9 | AC9 | Glass on API 34 (frost views attached, panel translucent); forced solid on API 34 (`--ez console_solid true`); solid on API 26 emulator, day and night, three pages | happy | screenshots, pixel samples | themes.xml, SphereFrostView, MainActivity.attachFrosts | glass: dots blurred under rail/panel; solid: surfaces opaque solid colours | screenshots |
| V10 | AC10 | animator_duration_scale 0, start the server, two screenshots 1 s apart during the load | edge | settings + screenshots | DotSphereView, ConsoleUi | identical frames (sphere, dots, light still) | diff = 0 |
| V11 | AC11 | Keyboard: Tab through Server, Backend, Tokens: ring visible on buttons, switch, segments, rows, fold; contrast test; font_scale 2.0 screenshots of each page | happy/edge | keyevents, screenshots | focus_ring_*, styles | ring visible on every pressable; ConsoleTokensTest contrast passes; no clipped text | screenshots |
| V12 | AC12 | Reasons: no model (switch), server off (tests), no token (copy/delete); busy labels on a running test, backup, restore; error strip with model missing; long model path ellipsis; empty authorised apps line; progress bar on copy | happy/edge | UI + screenshots | ControlsPolicy, render, layouts | each reason/busy/empty/error/long state shown | screenshots |
| V13 | AC13 | grep `appendLog("`, literal UI text in Kotlin and layouts; lint HardcodedText/SetTextI18n; no word matching on status | CLI | grep, lint xml | MainActivity, layouts, strings | none | outputs |
| V14 | AC14 | Generate shows no sheet; Delete token and Revoke access show sheets (Cancel keeps, action deletes); copy shows a brief message; no Toast class used | happy | UI + screenshots + grep | MainActivity, ConsoleUi | as stated | screenshots |
| V15 | AC14 | Explain cards: revoke POST_NOTIFICATIONS and RECORD_AUDIO, remove battery whitelist; launch: no prompt, no card; start server: no card in the way; ready: notifications card; Not now hides it and it stays hidden after a restart; battery card next, Allow opens the system screen, card gone on return; audio test without mic shows mic card, Allow -> system prompt, grant runs the test; deny twice -> card hides and brief message | happy/failure | pm revoke, UI | ExplainCardPolicy, MainActivity | as stated | screenshots |
| V16 | AC15 | `scripts/device.sh up` (Gemma 4 E2B, CPU), `api GET /v1/health`, streaming chat; `token` flow taps the Tokens tab | regression | device.sh | whole app | ready, 200, token saved | outputs |
| V17 | AC15 | UI tests from Diagnostics: Test chat, Test two turns, Check health, Check load, Test vision (image via picker), Test audio (emulator mic); each shows busy then logs | regression | UI | MainActivity tests | each completes and logs a result | screenshots, logs |
| V18 | AC15 | Tokens: generate, copy, delete, back up (CreateDocument picker), restore (OpenDocument picker), revoke | regression | UI + pickers | MainActivity tokens | each works; files written/read | screenshots, file listing |
| V19 | AC16 | `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`, `git diff --stat app/lint-baseline.xml` | CLI | gradle | all | pass; deletions only | outputs |
| V20 | AC17 | console-at-rest diff: tokens.json shadow block and elevations, tokens.css elevations, RULEBOOK 2.5 and change log | CLI | git diff, json parse | tokens.json, tokens.css, RULEBOOK.md | present and consistent | outputs |
| V21 | step 4 | Start, Home during load, return after ready: page shows ready | happy | adb | ServerStatusStore, onResume | READY | screenshot |
| V22 | step 4 | Rotate while ready: still ready | edge | adb rotation | same | READY | screenshot |
| V23 | step 4 | Switch off: off, stays off after Home and return | happy | UI | same | OFF | screenshots |
| V24 | step 4 | Start, send ACTION_STOP from adb during load, wait past the load: still off | failure | adb | ServerStatusStore, InferenceService | OFF, no late READY | screenshot, logcat |
| V25 | step 4 | Stop then start from adb quickly: reaches ready | edge | adb | same | READY | screenshot |
| V26 | step 4/7 | Start with the saved model file deleted: error shown and kept; error strip Try again / Select model | failure | run-as rm, UI | InferenceService, render | ERROR with detail; strip action | screenshots |
| V27 | step 7 | Vision test: busy survives the picker round trip and returning; stop the server mid-test: button leaves busy | edge | UI | busy set, render | as stated | screenshots |
| V28 | step 7 | Pick a model while off: progress bar runs, switch usable after copy | happy | picker | copyModelFile | as stated | screenshots |
| V29 | step 8 | Settings: change backend/MTP/encoder -> Saved; leave app and return -> kept; invalid context size -> red edge and reason, not saved; change while ready -> note shown; Escape/Back closes a sheet then returns to Server | happy/edge | UI | saveSetting, commitContextSize | as stated | screenshots, prefs dump |
| V30 | step 10 | Decode speed with app in foreground (sphere running) vs background (Home), same prompt, temperature 0 | non-functional | API timing script | DotSphereView | difference noted (no large drop) | script output |
| V31 | step 3 | Elevation numbers: brief message and sheet shadows compared by eye with the reference's lifted shadow | non-functional | screenshot vs reference | tokens elevation | resting 3 / lifted 8 look right, or adjusted | screenshots |

#### Coverage check
AC1 V1; AC2 V2; AC3 V3; AC4 V4; AC5 V5; AC6 V6; AC7 V7; AC8 V8; AC9 V9; AC10 V10; AC11 V11; AC12 V12; AC13 V13; AC14 V14 V15; AC15 V16 V17 V18; AC16 V19; AC17 V20. Plan steps 4, 7 and 8 emulator checks: V21-V29; step 10 device checks: V7 V9 V10 V11 V30 V31. Not coverable: a real pre-API 31 phone (API 26 emulator stands in); GPU and NPU (emulator has no OpenCL).

### Results (2026-11-25)
Commit verified: a26f8674a47ccbf08f15c3a65da91c42fa3f066e plus the uncommitted work; per-file `git hash-object` record (123 app files and the 3 console-at-rest files) in `ANDROIDEDGEAICORE-25-evidence/verify/hashes-results-1.txt`. Emulators: claude_edgeaicore_api34 (Gemma 4 E2B and Qwen3-0.6B, CPU) and the new claude_edgeaicore_api26 (`default/x86_64`). Evidence: `ANDROIDEDGEAICORE-25-evidence/verify/`, scripts in `ANDROIDEDGEAICORE-25-evidence/scripts/` (v.sh, grid.py, dots.py, ink.py, matrix.sh, speed.py, rawcheck.sh).

| ID | Status | Evidence and notes |
|----|--------|--------------------|
| V1 | PASS | raw-value check clean; ConsoleTokensTest 8/8; a changed colour made it fail (step 2) |
| V2 | PASS | crops: buttons, Danger, switch track, panels and header square the bottom left; sheet round on top, square below |
| V3 | PASS | only sora_*, instrument_sans_*, jetbrains_mono_* referenced; typewriter only on section names, status words, figures, code |
| V4 | PASS | no Main button, no emoji, no gradient; blue only on Quiet buttons, switch on, selected nav, status light, working dots, progress |
| V5 | FAIL | the sphere's centre follows every tap and stays there: after tapping the bottom bar or rail the dots sit at the bottom or left edge and most pages show almost none (S1, T1, V7 tablet and computer shots). Open vs reading strength, stars at night and stillness work; ripple and pulse not measured |
| V6 | PASS | fade patches match the page colour within 1 level, no visible edge; dots dim behind the words |
| V7 | PASS | 18 shots: bottom bar, rail, navigation panel with header status and tick rule; day and night; no crash |
| V8 | FAIL | edge to edge, 48 dp targets, sheets from the bottom, Back (Backend to Server, Server leaves), keyboard (box above it, bar steps aside) PASS; Escape on Backend or Tokens does nothing (expected: back to Server). Also: in landscape the display-cutout strip stays black |
| V9 | PASS | API 34 glass; forced solid panels #FAF9F6 day and #0E0E10 night; API 26 day and night solid |
| V10 | PASS | animations off: two frames 1 s apart while STARTING differ in 0 px |
| V11 | FAIL | rings visible on buttons, switch row, fold header, text box (blue edge and halo); none on bottom-bar items or segments (segment ring clipped by its track); contrast test passes; font scale 2 wraps, nothing clipped |
| V12 | PASS | reasons (select a model, start the server, generate a token, wait for the copy), busy labels and spinner, error strip, path ellipsis, empty apps line, copy progress bar |
| V13 | PASS | no appendLog literal, no layout literal text, no HardcodedText or SetTextI18n, no word matching on status |
| V14 | PASS | generate without a sheet; delete and revoke sheets with Cancel and the named red action; brief messages (Token copied, New token generated); no Toast |
| V15 | PASS | no card at launch or while starting; notifications card at READY; Not now persists across a restart; battery card, Allow opens the system screen, gone on return; mic card, Allow shows the system prompt, deny and deny-for-good hide it with the brief message; grant runs the test |
| V16 | PASS | device.sh up ready in 6 s, health 200, streaming chat 200, token via the Tokens tab |
| V17 | FAIL | chat, two turns, health, load and audio work; Test vision fails "unexpected end of stream": with the sphere running the streamed image request takes over 10 s to its first byte and the connection is dropped (curl empty reply at 10.0 s); background 6.6 s and works; develop works in the foreground |
| V18 | PASS | generate, copy, delete (confirmed), back up (file written to Downloads), restore (token and row back), revoke |
| V19 | PASS | 270 unit tests, lint 2 warnings that were already there, check_docs OK, baseline 770 deletions, 0 additions |
| V20 | PASS | tokens.json shadow block and androidElevation 3/8, tokens.css elevations, RULEBOOK 2.5 and change log |
| V21 | PASS | start while the app was in the background: READY on return |
| V22 | PASS | rotation keeps READY (rail frame in landscape) |
| V23 | PASS | switch off: OFF, stays OFF after Home and return |
| V24 | PASS | stop from adb mid-load: OFF stays, the late failure is dropped |
| V25 | FAIL | device.sh stop-service then start at once: the start reaches the same instance, then its onDestroy (from the stop's stopSelf) cancels the load; server down; UI shows OFF (the true state). With a 3 s gap the start works |
| V26 | PASS | missing model file: COULDN'T START, detail and error strip; Try again reaches READY |
| V27 | PASS | vision busy kept across the picker; stopping the server mid-test ends the busy state and shows the reason |
| V28 | PASS | picked Qwen3-0.6B: progress bar, Copying…, switch off with "Wait for the model to finish copying.", usable after |
| V29 | PASS | "0" shows the red edge and reason and is not saved; 2048 and CPU saved (prefs dump); note while running |
| V30 | FAIL | decode with the app in front (sphere running) 6.5-7.2 chunks/s, first token 2.1-2.8 s; background 19.9-20.4; develop in front 20.2-20.4; animations off 20.5. The sphere's frame loop costs about two thirds of CPU decode speed on the emulator |
| V31 | PASS | brief message and sheet read as lifted by eye; 3 and 8 kept |

Other findings: docs/testing-on-device.md names `system-images;android-26;google_apis;x86`, which cannot install the APK (it has only x86_64 and arm64-v8a libraries); the working image is `default;x86_64`. Notes (not failures): the busy spinner sits at the button's start edge, not beside the word; the open block shows only the top of the sphere at rest; the status line and the notice strip repeat the same error text; the bottom bar shows faintly through the 97% sheet.

Verdict: partly verified. PASS 25, FAIL 6 (V5, V8, V11, V17, V25, V30), BLOCKED 0; plus the docs finding above.

### Note (2026-11-25): V25 superseded by V32
V25 expected READY after `device.sh stop-service` and an immediate `start`. Android destroys the instance after the start reaches it (the stop's `stopSelf`), cancelling the load; develop behaves the same. The requirement only asks that "Server start, the six tests, ... still work", and the plan's Approach says the store shows the true state. Added:

| ID | Requirement / success criterion | Scenario | Type | Method | Files | Expected result | Evidence |
|----|--------------------------------|----------|------|--------|-------|-----------------|----------|
| V32 | step 4 (store shows the true state) | adb stop then start at once; then `GET /v1/health` | edge | adb, API | ServerStatusStore, InferenceService, MainActivity | the page ends in the state the service is in (OFF with health unreachable, or READY with health 200), never stuck at STARTING | screenshot, health output |

### Results (re-run 1, 2026-11-25)
Round-1 build (sphere 10/30 fps, navigation-only frost, Escape, focus rings, touch release); emulator booted fresh. Uncommitted files changed against the first results; record superseded by re-run 2's.

| ID | Status | Evidence and notes |
|----|--------|--------------------|
| V5 | PASS | holding a touch at the bottom pulls the sphere there; after release it returns to the middle (R1-V5-holding-bottom/released); commit ripple: dot spread 395 -> 415 -> 391 after Generate token (R1-V5-before/ripple/after) |
| V8 | PASS (Escape) | Escape on Backend -> Server, on Server nothing, closes the sheet first; Back on Server -> launcher |
| V11 | PASS | rings on bottom-bar items (R1-V11-nav_*), segments (R1-V11-rb_cpu), rail (R1-V11-rail-nav_server) and navigation panel (R1-V11-panel-focus) |
| V16 | PASS | device.sh up ready in 9 s, health 200, streaming chat 200 |
| V30 | PASS | foreground 17.2-17.5 chunks/s (app idle CPU 13.5%), background 20.0 |
| V17 | FAIL | API streamed vision in the foreground answers (first byte 7.7 s), but Test vision from the UI fails "unexpected end of stream" (the test's own spinner, dots and light pulse at full rate) |
| V7 | PASS | 18 shots, three widths, day and night (R1-V7-*) |
| V9 | PASS (glass) | rail frosts the dots behind it (R1-V9-rail-frost) |
| V33 | BLOCKED | sphere pulse while working: not distinguishable in screenshots (drift dominates; spread range 21-22 in both states); covered only by DotSphereModelTest.workingSpeedsUpTheTurn |

### Results (re-run 2, 2026-11-25)
Commit verified: a26f8674a47ccbf08f15c3a65da91c42fa3f066e plus the uncommitted work; per-file hashes (126 files) in `ANDROIDEDGEAICORE-25-evidence/verify/hashes-results-3.txt`. Round-2 build (slow shared ticker for dots, pulse, spinner). Fresh emulator boots; API 26 emulator for solid.

| ID | Status | Evidence and notes |
|----|--------|--------------------|
| V1 | PASS | raw-value check clean; ConsoleTokensTest passes |
| V2 | PASS | panels, card, buttons, segments, sheet (R2-V15-card, R1 crops) |
| V3, V4, V6, V13, V20 | PASS (carried; files unchanged or re-checked on screenshots) | |
| V5 | PASS (re-run 1) | DotSphereModel/View unchanged in round 2 |
| V7 | PASS (re-run 1) | layouts unchanged in round 2 |
| V8 | FAIL | Escape, Back, sheets, keyboard, 48 dp targets PASS; in landscape the display-cutout strip stays black (R2-V22-landscape): the window does not draw into the cutout |
| V9 | PASS | forced solid on API 34 (#FAF9F6 panel), API 26 three pages (R2-V9-*), glass rail frost (re-run 1) |
| V10 | PASS | animations off: during STARTING only the detail line changed (7,428 px in the line's box), no dot, light or sphere movement |
| V11 | PASS (re-run 1) | styles and drawables unchanged in round 2 |
| V12 | PASS | reasons (tests, token, copy), busy spinner, error strip, copy progress (R2-V28-copy) |
| V14, V18 | PASS | generate without a sheet; copy; back up written; delete and revoke sheets confirmed; restore row back (R2-V14-V18.txt) |
| V15 | PASS (light) | no card at launch; notifications card at READY (R2-V15-card) |
| V16 | PASS (re-run 1) | |
| V17 | PASS | Test vision from the UI: "The image is a solid, bright red color..." (R1-V17-vision-busy shows the slowed spinner); RenderThread 27% during a test |
| V19 | PASS | 274 unit tests, lint 2 old warnings, check_docs OK, baseline deletions only |
| V21, V23, V24, V26, V27 | PASS | background start READY; OFF kept; stop mid-load OFF; missing model -> Couldn't start + Try again -> READY; stop mid-test ends busy (R2-status-scenarios.txt) |
| V22 | PASS | rotation keeps state, rail frame |
| V25 | superseded by V32 | |
| V28, V29 | PASS | copy with Copying… and the wait reason; Off saved; invalid 0 shows the reason |
| V30 | PASS | foreground 16.8-17.1 chunks/s vs background 20.1 (-15%) |
| V31 | PASS (carried) | |
| V32 | PASS | stop then start at once: OFF with health unreachable, never stuck at STARTING |
| V33 | BLOCKED | see re-run 1 |

Verdict: partly verified. FAIL 1 (V8 landscape cutout), BLOCKED 1 (V33), all others PASS.

### Results (re-run 3, 2026-11-25)
Commit verified: a26f8674a47ccbf08f15c3a65da91c42fa3f066e plus the uncommitted work; hashes in `ANDROIDEDGEAICORE-25-evidence/verify/hashes-results-4.txt`. Round-3 build (one line in MainActivity.setUpEdgeToEdge).

| ID | Status | Evidence and notes |
|----|--------|--------------------|
| V8 | PASS | landscape: the left cutout strip is the page colour (244,242,238) and the rail sits clear of it (R3-V8-landscape, R3-V8-landscape-backend); portrait top bar unchanged (R3-V8-portrait) |
| V22 | PASS | landscape frame renders, no crash |
| all others | as re-run 2 | no other code changed |

Verdict: verified except V33 (BLOCKED: the sphere's pulse while working cannot be told apart in screenshots; unit-tested only). PASS for every other scenario; V25 superseded by V32.

## Review history

### Round 1 - 2026-11-25
Triage (resolve-review):

| ID | Sev | Decision | Reason |
|----|-----|----------|--------|
| R1-1 | MAJOR | Valid | errorAction has no copy check; the switch has one |
| R1-2 | MAJOR | Valid | rule book 3.3; the detail moves to the top of the server panel |
| R1-3 | MINOR | Valid | strings 70-71 carry the attempt and the exception text |
| R1-4 | MINOR | Valid | rule book 2.5: resting = panels and cards, 3 dp on Android |
| R1-5 | MINOR | Valid | the dialog is not kept or dismissed |
| R1-6 | MINOR | Valid | Float time and angle |
| R1-7 | MINOR | Deferred | the copy runs in an unscoped coroutine bound to the old activity (base code); tracking it across recreation needs a process-wide copy owner, a design change; follow-up |
| R1-8 | MINOR | Valid | no test pins the order |
| R1-9..R1-11 | MINOR | Valid | docs |
| R1-12 | MINOR | Valid | cheap to remove the cap; a word longer than the segment still wraps by letter, checked live in re-verify |
| R1-13..R1-17 | NIT | Valid | |

Plan: fixing 16, deferring 1 (plus the 6 pre-existing), rejecting 0.

Verdict: Request changes. Scope: round 1, whole change against a26f8674 (working tree, all changed files) plus console-at-rest tokens.json, tokens.css, RULEBOOK.md; requirement: Description, Acceptance criteria, Reviewer brief. Reviewers: two lenses split by file group (Kotlin, resources/docs), four Haiku reviewers in all.

- R1-1 [MAJOR] ControlsPolicy.kt:82 - errorAction ignores MODEL_COPY: with the server in error and a copy running, the notice's "Try again" calls requestStart() on a model still being written, which the switch forbids (WAIT_FOR_COPY) - Static - Fixed - ControlsPolicy.kt (Controls.errorActionEnabled = !copying), MainActivity.kt render() (notice action enabled from it); test ControlsPolicyTest.theErrorActionWaitsForAModelCopy (failed to compile before the fix)
- R1-2 [MAJOR] page_server.xml:101 - tv_status (up to 3 lines of detail, plus the address) sits on the sphere; rule book 3.3 allows a status line, headline and up to three short lines, longer goes on a panel; at font scale 2 it fills the top block (V11-font2-server.png) and is cut at 3 lines - Static - Fixed - page_server.xml (tv_status moved from open_block to the top of server_panel, no line cap), MainActivity.kt render() (detail hidden in ERROR, where the notice strip shows it)
- R1-3 [MINOR] strings.xml:71 / InferenceService.kt:247 - ping failure status shows the attempt number and the raw exception message (Part 9: say what happened and what to do) - Static - Fixed - strings.xml status_ping_success/status_ping_failed (no attempt number, no exception text), InferenceService.kt verifyEngineReady() (error kept in the debug log only)
- R1-4 [MINOR] tokens_dimens.xml:61 - elevation_rest is never used: the glass panels (rail, navigation panel, wide content panel) have no resting shadow, unlike the reference's .glass (box-shadow: var(--shadow)) - Static - Fixed - pieces.xml elevation_none, frames.xml/values-w600dp/frames.xml page_panel_elevation (none on phone, rest on wider frames), page_server/page_backend/page_tokens panels, layout-w600dp and layout-w1024dp activity_main header panels, styles.xml Rail, NavPanel (elevation_rest instead of move_rest) and ExplainCard
- R1-5 [MINOR] ConsoleUi.kt:64 - the confirm BottomSheetDialog is not dismissed when the activity is destroyed; rotation with the sheet open leaks the window - Not reproduced (not attempted) - Fixed - ConsoleUi.kt confirm() keeps the open sheet, dismissSheet(); MainActivity.kt onDestroy() closes it (not shown failing on a device; UI-only class with no test class, per the owner decision)
- R1-6 [MINOR] DotSphereModel.kt:190,196 - now and spin grow without bound as Float; after hours of uptime sin(now * rate) loses precision and the drift steps - Static - Fixed - DotSphereModel.kt (now and spin are Double, spin kept within one turn; advance(), project()); test DotSphereModelTest.aShortStepStillMovesTheSphereAfterDaysOfRunning (failed before the fix)
- R1-7 [MINOR] MainActivity.kt:143 - busy set (model copy, tests) is lost on recreation, so Select model and the switch re-enable mid-copy after rotation - Static - Deferred - the copy runs in an unscoped coroutine bound to the old activity (base code); keeping busy across recreation needs a process-wide copy owner. Follow-up in the PR notes
- R1-8 [MINOR] ServerStatus.kt:4 - the enum's order must match status_light.xml levels but no test pins it (requirement 9: an enum with a unit test) - Static - Fixed - new ServerStatusTest (enum order against status_light.xml levels and colours); docs/standards/testing.md lists it
- R1-9 [MINOR] docs/pitfalls.md:42, docs/standards/api.md:51 - still name the old "Backend Settings" tab - Static - Fixed - docs/pitfalls.md item 20, docs/standards/api.md context size line
- R1-10 [MINOR] docs/architecture.md:24, docs/standards/testing.md:6 - LoopFrames and LoopFramesTest not listed - Static - Fixed - docs/architecture.md UI pieces row, docs/standards/testing.md test list
- R1-11 [MINOR] docs/testing-on-device.md:51,53 - polls for `READY` but uiautomator reads "Ready"; look commands give only the 1024dp+ width, not a 600-1023dp one - Static - Fixed - docs/testing-on-device.md UI flow (Ready) and Look line (rail and navigation panel frames)
- R1-12 [MINOR] styles.xml:95 - segment labels capped at 2 lines with ellipsis; at font scale 2 on a 360dp phone "Adaptive" may break mid-word or be cut - Not reproduced (the font-2 screenshots do not show the Speculative row) - Fixed - styles.xml Widget.Console.Segment (no line cap or ellipsis); checked live in re-verify
- R1-13 [NIT] MainActivity.kt:170 - an OFF status with an empty detail logs a bare "Status: " - Static - Fixed - MainActivity.kt statusReceiver (no log line for an empty detail)
- R1-14 [NIT] DotSphereView.kt:278 - DOT_COLOURS hard-codes 5 instead of the colour list's size - Static - Fixed - DotSphereView.kt (DOT_COLOURS is the colour id list; palette and count come from it)
- R1-15 [NIT] RULEBOOK.md:5 - header still reads 11 November 2026 after the 25 Nov change-log row - Static - Fixed - console-at-rest RULEBOOK.md header (last changed 25 November 2026)
- R1-16 [NIT] activity_main.xml:52, layout-w1024dp/activity_main.xml:82,90, page_server.xml:146,193,367 - stray indentation of tools:ignore - Static - Fixed - layout/activity_main.xml, layout-w1024dp/activity_main.xml, layout/page_server.xml (indentation)
- R1-17 [NIT] README.md:28 - "side panel" where the UI calls it the navigation panel - Static - Fixed - README.md tabs line
- R1-18 [MINOR] InferenceService.kt / picker - Select model is enabled while the server runs, so a copy can overwrite the model in use - Pre-existing
- R1-19 [MINOR] MainActivity.kt model copy - a null input stream counts as a successful copy - Pre-existing
- R1-20 [MINOR] InferenceService - stop followed at once by start reaches the instance Android then destroys (reporter identity sees one instance) - Pre-existing
- R1-21 [MINOR] the switch cannot stop a server that is STARTING - Pre-existing (base also locked it)
- R1-22 [NIT] MainActivity.kt:918 - unused Intent built before restoreTokensLauncher.launch - Pre-existing
- R1-23 [NIT] README.md:32 - lists .bin and .tflite models - Pre-existing
Dismissed:
- No status-kind extra on the broadcast: the plan rejected a new extra (status reached in the background would be lost); ServerStatusStore carries the kind and MainActivity switches on it.
- Content panel not blurred: fix-loop decision for V30/V17 (blur cost decode speed 20 -> 6.7 chunks/s); rule book 2.1 gives content panels almost no blur; listed in PR notes.
- Nav item focus ring drawn inside the item: the bar edge clips anything outside; documented in nav_item_background.xml; V11 passed.
- "Saving" lacks an ellipsis: rule book 7 settings rule says "Saving" then "Saved".
- Raw strokeWidth 2 in nav icons: viewport units of a 24 grid, icon geometry like pathData.
- Two bundled fonts unused: the approved design lists all seven weights.
- Outline button and switch-off edge contrast: the reference's .btn-outline and .switch use --edge-strong as written; a design-system question, not this app's.
- Bottom bar labels clipped at 200%: V11-font2 screenshots show them whole.
- MainActivity growth, several types in ControlsPolicy.kt, KDoc wording, letterSpacing 0, move_rest name, test-prompt punctuation, licence wording, weak-test claims: style preferences with no rule broken, or tests already assert the behaviour.
Resolution: 16 fixed, 1 deferred (R1-7), 0 rejected; 6 pre-existing deferred. Checks: ./gradlew testDebugUnitTest lintDebug (277 tests pass; lint 2 warnings, both already there, baseline unchanged), scripts/check_docs.sh OK. Runtime behaviour changed: ControlsPolicy.kt, MainActivity.kt, ConsoleUi.kt, DotSphereModel.kt, DotSphereView.kt, InferenceService.kt, strings.xml, styles.xml, frames.xml, pieces.xml, page_server/page_backend/page_tokens.xml, layout-w600dp and layout-w1024dp activity_main.xml; re-verify needed.

## Log
- 2026-11-24: created from the approved architecture
- 2026-11-25: plan drafted, unresolved issues (5 validation rounds plus 2 after owner decisions; last-round fixes not re-validated)
- 2026-11-25: plan rewritten from a full code read and validated with minors logged (rounds: 5, 2 after owner decisions, 2 added by the owner's request and after the rewrite)
- 2026-11-25: autopilot started (stage 2 implement next; plan already validated)
- 2026-11-25: implemented (stage 2), smoke tests pass: 270 unit tests, lint and docs checks clean
- 2026-11-25: verification: partly verified, 25 PASS, 6 FAIL (V5 sphere follows taps, V8 Escape, V11 focus rings on bar and segments, V17 vision in foreground, V25 stop+start race, V30 decode speed with sphere running), 0 BLOCKED
- 2026-11-25: fix rounds 1-2; re-run 2: 1 FAIL (V8 landscape cutout), 1 BLOCKED (V33 pulse), rest PASS
- 2026-11-25: fix round 3; re-run 3: all PASS except V33 BLOCKED (pulse not measurable on screen)
- 2026-11-25: review round 1: Request changes (2 MAJOR, 10 MINOR, 5 NIT open; 6 pre-existing)
- 2026-11-25: review round 1 resolved: 16 fixed, 1 deferred, 0 rejected; 277 tests, lint and docs pass
