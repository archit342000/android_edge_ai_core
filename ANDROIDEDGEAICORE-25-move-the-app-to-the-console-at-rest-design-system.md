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
- [ ] No raw colour, size, corner, shadow or duration in layouts, styles or UI code; all come from the token resources, and the parity test against the `tokens.json` snapshot passes.
- [ ] Every shape squares its bottom-left corner, or is a listed exception (circles, bottom sheet, edge-to-edge surfaces).
- [ ] Only Sora, Instrument Sans and JetBrains Mono appear, each in its own job; typewriter labels only on section names, figures, times and status.
- [ ] Blue appears only on things that can be pressed or are selected; at most one Main button per screen; no gradients, glows or emoji icons.
- [ ] The dot sphere is present on every screen at the right strength: open at the top of Server, reading strength after scrolling and on Backend and Tokens; still stars at night; it reacts to touch, commits and work.
- [ ] Words on the sphere have a fade behind each line; everything longer is on a panel.
- [ ] Day and night both pass, on a phone width, a 600 to 1023dp width and a 1024dp or wider width, with the bottom bar, rail and navigation panel respectively.
- [ ] Edge to edge; touch targets at least 48dp; confirms rise from the bottom; Back undoes the newest layer and leaves the app only from Server; the keyboard never covers the focused field.
- [ ] Glass mode works on API 31 and later and solid mode below it; both were checked.
- [ ] With system animations off nothing moves, including the sphere.
- [ ] With a keyboard everything is reachable and the focus ring is visible; text contrast is at least 4.5 to 1; nothing is clipped at 200% text size.
- [ ] Every switched-off control with a changeable cause says the reason; buttons show a busy state; empty, loading, error and long-text states exist.
- [ ] All user-visible text is in `strings.xml` and follows Part 9 of the rule book; `MainActivity` no longer decides state from words in the status text.
- [ ] Only delete token and revoke access ask for confirmation; brief messages replace toasts; an explain card with "Not now" precedes each permission prompt.
- [ ] Server start, the six tests, token generate, copy, delete, backup, restore and revoke still work, and `scripts/device.sh` flows still pass.
- [ ] `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass with no new lint baseline entries; README and docs match the new UI.
- [ ] `tokens.json` in the console-at-rest repo carries the shadow values, with a change-log line.

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
- Work files: none yet
- Counters: fix 0/10, review 0/5, plan amendments 0/3, re-verify 0
- Last completed stage: 1 plan (validated before this run)  Next: 2 implement
- Hard blocker: none
- WIP commit: none

### Autopilot decisions (2026-11-25)
- Pre-flight: the request "work on this ticket till it's done" names ticket 25 from the conversation, so the run is in ticket mode for ANDROIDEDGEAICORE-25.
- Pre-flight: stage 1 skipped; the ticket's Plan is already `Validated - minors logged` with all owner decisions taken.
- Pre-flight: used the checkout at ~/Coding/repos (on `develop` at the latest origin commit) rather than the older one under docker-data.
- Pre-flight: branch `feature/console-at-rest-design-system` from `develop` (gitflow: `feature/<topic>`, lowercase kebab).

## Verification

## Review history

## Log
- 2026-11-24: created from the approved architecture
- 2026-11-25: plan drafted, unresolved issues (5 validation rounds plus 2 after owner decisions; last-round fixes not re-validated)
- 2026-11-25: plan rewritten from a full code read and validated with minors logged (rounds: 5, 2 after owner decisions, 2 added by the owner's request and after the rewrite)
- 2026-11-25: autopilot started (stage 2 implement next; plan already validated)
