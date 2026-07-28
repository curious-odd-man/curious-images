# Random Memory

## What it does
One click → show one random past photo (with context, e.g. its date/location).

## Depends on shared infra
- §A (Discover screen — a single button) and, loosely, §B, though this is simple enough it may not
  need a persisted `insight_card` at all.

## New data
None.

## Backend design
- `MediaRepository.findRandom()` — simplest possible implementation: Postgres
  `ORDER BY RANDOM() LIMIT 1` is fine at this library's scale (tens of thousands of rows, not
  millions); avoid over-engineering this one. Optionally weight away from photos shown very
  recently by this same feature (track `last_shown_at` similar to `insight_card`, or just accept
  true randomness for v1).

## UI
- A single button in the Discover screen (§A) or even the main toolbar: "Show me a random memory,"
  opening the photo in the normal full/detail view with its date and place shown.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../persistence/MediaRepository.java`
- `src/main/java/.../ui/controller/custom/RightPanelController.java` (for showing date/place)

**New:**
- `MediaRepository.findRandom()` query addition
- A button/handler in the Discover screen (or toolbar) wired to open the result
