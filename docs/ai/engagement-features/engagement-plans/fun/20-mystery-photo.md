# Mystery Photo

## What it does
A simple guessing game: show one random photo, ask "what year was this?", reveal the answer.

## Depends on shared infra
- Reuses `MediaRepository.findRandom()` from feature 19 directly — do not write a second random
  selector.

## New data
None required for the game itself. Optionally a `mystery_photo_stat` table if a running score/
streak is wanted (guessed-correctly count), but that's a nice-to-have, not core to the spec.

## Backend design
- Essentially pure UI logic: pick a random photo (reuse feature 19's query, maybe biased toward
  photos with a confidently-known `capture_date_source` so the "correct" answer isn't itself
  uncertain — check `CaptureDateSource.java` for how date confidence is already modeled), present
  it without its date, collect a year guess, compare to `capture_date`'s year, reveal.

## UI
- A small modal/game screen off the Discover screen: photo + year slider/input + "Reveal" button +
  result feedback ("You were 2 years off!").

## Files to hand to the implementing AI
**Existing (context):**
- `19-random-memory.md` (shared random-photo query)
- `src/main/java/.../domain/imports/metadata/CaptureDateSource.java` (date-confidence filtering)

**New:**
- `src/main/resources/fxml/mystery_photo.fxml` + `MysteryPhotoController.java`
