# Mobile pinch zoom and clock audit — 2026-10-29

Base: `92435d7` (`main`, merged resume-rewind changes).

## Request and findings

The phone player needed pinch zoom with snaps at display width/height. Its common touch layer previously handled only taps and double taps. All nine video paths use that layer: Media3, VLC and MPV for library media, YouTube and TVHeadend. Existing zoom normalization stopped at 200%, insufficient for filling the height of a portrait phone with a landscape movie. The zoom menu also labelled every non-preset value as the 100% selection.

Clock preferences were stored and observed correctly. Home and PageHeader explicitly excluded handheld clocks, the mobile settings header had no clock, and PageUtilityBar omitted clocks on detail pages. ReferencePlayerClock separately hard-coded white, 22sp and medium weight, ignoring the shared appearance settings.

## Implementation decisions

- Use the measured player viewport and the decoder's display aspect ratio, including pixel aspect ratio, with source metadata as an opening fallback where available. Freeze geometry during each gesture.
- Snap to the two aspect-preserving FIT-relative edge scales. Keep raw finger movement separate from displayed snapping. Entry/release hysteresis adapts to closely spaced targets; a fast crossing is held until deliberate further movement.
- Allow 50–800% zoom. Fixed-format normalization is unchanged until a real pinch passes touch slop, which deliberately selects the source aspect ratio and FIT plus zoom. Invalid geometry produces no zoom update.
- Consume multi-touch through the last finger release, including a two-finger contact without movement, so ending a pinch cannot pause, seek or toggle the OSD. Stable pointer-input keys and fresh callbacks keep recomposition from restarting a gesture.
- Keep the touch layer below controls and disable pinch while a settings panel is open or audio-only playback is active. TV remote behavior is unchanged. A viewport resize clears a gesture-created crop.
- Update screen state and backend presentation together. Show the exact custom zoom in the shared video panel alongside the existing reset/preset options.
- Keep native VLC/MPV buffers at the unzoomed layout size and apply centered SurfaceView scaling. This avoids reallocating very large output buffers on every pinch frame; clipping remains on the host. Media3 already scales its video surface.
- Use AmbientClock in the shared player clock. Restore clocks in handheld Home/page/settings headers and detail utility bars. Avoid duplicates in filtered browsing. Reserve actual layout space on phone headers; long inline dates yield to time, and mobile time fits its available width without changing the saved font size.
- Preserve visibility modes: headers-only excludes playback; headers plus player includes it. Preserve the screensaver's separate brightness/OLED behavior; it intentionally shares only time format and date style.

## Verification

- 21 actual Kotlin/JUnit tests passed for pinch geometry and hysteresis, portrait fill, invalid inputs, zoom bounds, source-format conversion, existing presentation geometry and custom menu percentages.
- 10 unchanged OSD presentation regression tests passed, for 31 executed relevant Kotlin tests in total.
- The local JVM runner uses the project's Kotlin 2.4.21 compiler. Its only shims are AndroidX annotations and Media3 resize-mode constants; production geometry and zoom logic are compiled unchanged.
- All 23 edited/new Kotlin files pass PSI syntax parsing, and `git diff --check` passes. PSI parsing does not replace Android type checking.
- The source audit matches the baseline exactly: 93 existing missing `values-en` entries, no new findings.
- Added Robolectric coverage checks stable native surface dimensions, centered scaling, clipping and fixed-format reset. These view tests and full Android/Compose compilation have not run: this workspace has no configured Android SDK/JDK 21. The repository's PR checks can run them after publication is authorized; the initial GitHub upload was blocked by automatic approval review, so no PR/CI result exists for this change yet.

## Physical-device checks still required

1. Phone landscape/portrait, 16:9, 4:3 and 21:9 video: slowly reach each edge, leave it in either direction, cross quickly, then lift fingers without triggering playback controls.
2. Keep one finger down, rejoin with the second, and continue pinching. Rotate or resize the window after zooming, including while a video-settings panel is open.
3. Verify library media, YouTube and TVHeadend with each available renderer, including HDR hardware surfaces; confirm no picture flash, playback restart or audio interruption during scaling.
4. Verify Off/Home/Headers/Headers+player clock modes on Home, system overview, detail, filtered browsing, settings and each player. Change accent/dim/white, opacity, font, size, shadow, date and seconds.
5. On a narrow phone, check large monospace 12-hour time with seconds and a long inline date, including increased system font scaling. Check TV focus and utility controls after the clock restoration.

No physical phone, Android emulator or native playback device was connected during this change.
