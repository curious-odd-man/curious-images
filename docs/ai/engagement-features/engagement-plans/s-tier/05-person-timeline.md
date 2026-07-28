# Person Timeline

## What it does
For a given identified person, show their photos across time as a timeline — a natural extension
of the existing per-person detail screen. (The source doc lists this under S-tier with almost no
elaboration beyond the header; scope it as: a chronological, date-bucketed view of every photo
containing that person, distinct from today's likely-grid-only `PersonDetailController`.)

## Depends on shared infra
- Existing `PersonRepository`, `FaceRepository`, `PersonDetailController`/`person_detail.fxml` —
  this is additive to a screen that already exists, not a new subsystem.
- §D only if a "where has this person been photographed" map is added as part of the timeline.

## New data
None required. Query pattern: all `media` joined through `face`→`person_id = X`, ordered by
`capture_date`, bucketed by year (and optionally month) for a scrubbable timeline control.

## Backend design
- Check `PersonDetailController`/`person_detail.fxml` first — confirm whether it already renders a
  grid-only view or has any chronological affordance. Add:
  - `PersonRepository.findMediaTimelineBuckets(personId)` → `List<TimelineData>` (the existing
    `model/TimelineData.java` may already exist for exactly this purpose — check before creating a
    new DTO).
  - A timeline scrubber UI component (year markers + jump-to-year), likely a custom control added
    to `person_detail.fxml`, backed by `PersonDetailController`.
- Nice-to-have (matches "Discovery → Then vs Now" spirit): highlight the earliest and most recent
  photo of the person prominently ("first photo of X" / "most recent photo of X").

## UI
- Extends the existing person detail screen rather than adding a new one. Add a timeline
  scrubber above/beside the existing photo grid in `person_detail.fxml`.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../ui/controller/screen/PersonDetailController.java`, `resources/fxml/person_detail.fxml`
- `src/main/java/.../persistence/PersonRepository.java`, `FaceRepository.java`
- `src/main/java/.../model/TimelineData.java` (verify current usage before extending)

**New/modified:**
- Additions to `PersonRepository` (`findMediaTimelineBuckets`)
- Modifications to `PersonDetailController.java` and `person_detail.fxml` (timeline scrubber)
- New small custom control, e.g. `ui/controller/custom/TimelineScrubberController.java` +
  `timeline_scrubber.fxml`, if a reusable scrubber control is worth extracting (it will also be
  useful for On This Day and Monthly Story timelines — consider building it once, shared).
