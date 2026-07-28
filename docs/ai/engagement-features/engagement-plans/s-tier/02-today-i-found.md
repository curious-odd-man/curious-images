# Today I Found...

## What it does
One interesting, varied discovery card shown once per app launch — e.g. "You photographed this
bridge 17 times over 8 years", "You haven't seen these photos since 2018", "This person appears in
4,382 photos". Exactly one card per launch is the spec — this is a *selection/surfacing* feature,
not a generation feature; the actual discoveries come from the other generators.

## Depends on shared infra
- §B entirely — this feature *is* the "pick one card to show on launch" behaviour, drawing from
  the pool of `insight_card` rows produced by every other generator (On This Day, Hidden Patterns,
  This Never Happened Again, Archive Explorer, Rediscover Forgotten Photos, Then vs Now, Places
  You've Returned To all contribute candidates into the same pool).
- §A for where the popup/banner lives.

## New data
None — purely a consumer of `insight_card`.

## Backend design
- `domain/insights/LaunchCardSelector`: called once at application startup (hook into
  `ApplicationMain`/`JavafxApplication` or a `StartupRunnable`, see existing
  `util/StartupRunnable.java`). Selects the single best unshown-or-stalest card across *all*
  types via `InsightCardRepository.findNextUnshown()` (weighted by `score`, excluding
  `dismissed_at IS NOT NULL`, excluding types already shown in the last N days to keep variety).
- On selection, calls `InsightCardRepository.markShown(id)`.
- This generator does not compute anything itself; if the `insight_card` pool is empty (e.g. first
  run before `InsightGenerationJob` has executed once), it should silently show nothing rather than
  block startup — the job runs asynchronously per §B.

## UI
- A lightweight popup/toast/banner using the existing `NotificationsService`/
  `event/model/UserNotificationEvent` pattern (`NotificationMenuItemController`,
  `notification_menu_item.fxml`) rather than a full modal — this should feel like a passive nudge,
  not an interruption blocking the library view.
- Tapping it opens the Discover screen (§A) scrolled to that card.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../util/StartupRunnable.java`
- `src/main/java/.../ui/controller/services/NotificationsService.java`
- `src/main/java/.../event/model/UserNotificationEvent.java`, `event/payload/UserNotificationPayload.java`
- `00-shared-infrastructure.md` (§B, §A)

**New:**
- `src/main/java/.../domain/insights/LaunchCardSelector.java`
- (repository methods `findNextUnshown`/`markShown` live on the shared `InsightCardRepository`
  from feature 01/§B — do not duplicate them here)
