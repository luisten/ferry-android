Copied unchanged from github.com/luisten/ferry-messenger `FerryTests/Fixtures/` at 2eddfd3 (the engine this repo pins).

- `engine_*.json`: what the engine (`core/chat`) makes of Instagram's captures, written there by `go test ./chat -update`.
  When the pin moves and the engine's output changed, copy them again from the new commit.
- The rest: Matrix-style events and syncs from Ferry's bridge (live checks M2–M8), scrubbed. The engine kept the bridge's
  shapes, so they still describe what the app reads; the bridge-only parts in them (its bot, personal spaces, send
  statuses) are not modelled here, and the tests that load them say where that matters.
