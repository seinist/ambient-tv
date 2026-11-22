# Game OSD focus and panel integration audit — 2026-11-22

## Completed feature

Games use the common `PlayerPanelNavigation` origin stack and mounted-control registry.
`GamePanelNavigation` stores typed game destinations so core category/option pages and
picture shader groups remain distinct levels. `GamePanelOverlay` still keys each level
to its own animated dismissal state.

The shared navigation adds one `GAME` destination with an opaque page identity; existing
video destinations and their ordinary `open`/`back` behavior are unchanged. The video
panel renderer explicitly ignores game-owned content. Both use the same bounded,
frame-based focus hand-back implementation.

## Focus rules

- First OSD opening focuses Continue when available, otherwise the permanent Settings
  control. A panel closing does not trigger that initial-open rule again.
- Toolbar controls retain their existing `fromOrigin` registration. Game panel choices
  register a stable row key and capture it before opening a child. Back restores that
  row; closing the whole path restores its originating toolbar button.
- Core option/category rows use their native keys, so duplicate translated titles do
  not alias focus targets. Other game rows keep the same shared focus visuals.
- The state strip retains its originating button independently of the panel stack.
  It therefore does not make the shared OSD hide itself. Closing returns to States;
  opening a real panel discards the strip's old return request.
- Unavailable panel choices are omitted. If a former return target is gone, the shared
  retry ends and the game falls back to Continue or Settings. A currently focused
  toolbar action disappearing during a busy operation is also recovered.
- Registration changes and window-focus changes drive bounded focus retries. There is
  no continuous polling or repeated focus reset during ordinary navigation. A panel, state
  strip, dialog, current focus or pending hand-back prevents competing recovery.
- Resume/exit discards panel and strip state and cancels pending/failed focus returns.
  A late Back callback is checked against its original typed page and cannot pop a
  replacement page.

## Integration audit

Reviewed the main player's wiring to `rememberGamePanelNavigation`, `GameSidePanel`,
`GamePanelChoice`, `LocalPlayerPanelNavigation`, state-strip closing and the permanent
Settings requester. The old effect keyed by panel/state-strip visibility is removed.

The same review found two state-action interactions outside the focus helper:

1. Successful loading called the newly busy-guarded resume before clearing busy. The
   player now clears busy before successful resume, while retaining its final cleanup.
2. The availability snapshot updates at recomposition. The save callback now also
   checks live busy/destroyed/exiting/lifecycle flags, preventing duplicate same-frame
   save jobs before that snapshot changes.

The option panel remains offered when stored override maps can be reset even if the
native core currently advertises no option rows. Otherwise unavailable core options
are hidden using the same availability policy as the toolbar.

## Regression coverage and validation

- Eight `GamePanelNavigationTest` scenarios cover nested origins, direct root panels,
  shader pages, stale Back callbacks, the independent state strip, resume cancellation,
  direct hardware roots and rejected subpages on another panel.
- Four real Compose tests use the production shared toolbar, game side panels and
  animated overlay to check each nested core return, state-strip return, removed-origin
  fallback and disappearing busy controls. The unavailable settings row is asserted
  absent from semantics.
- Existing shared video navigation/focus lifecycle tests remain the regression gate
  for the common-stack extension.
- `git diff --check` passes. The complete new game navigation helper compiles with the
  Kotlin Compose plugin and real Compose APIs in a local host harness. The action policy,
  eight game-path tests and existing shared navigation/registry/rescue tests pass as one
  50-test run. The shared navigation/model declarations are extracted unchanged; the
  surrounding Chrome and panel views are compile-only boundaries. Full Android and
  Robolectric UI execution remain the PR CI gate.
- This audit does not claim physical phone/TV/controller execution.
