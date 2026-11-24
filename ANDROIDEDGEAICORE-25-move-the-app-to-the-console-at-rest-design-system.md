---
id: ANDROIDEDGEAICORE-25
title: "Move the app to the Console at Rest design system"
status: todo
created: 2026-11-24
updated: 2026-11-24
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

## Implementation notes

## Verification

## Review history

## Log
- 2026-11-24: created from the approved architecture
