# Auto-Generated Album Titles

## What it does
Replace generic album names (currently the plain date string / rounded GPS coordinate produced by
`AlbumGenerationJob`) with something readable, e.g. "Weekend in Prague" instead of `IMG_2024`.

## Depends on shared infra
- §C (Ollama text) — the naming step is a short LLM call over a *structured summary* of the
  album's contents, not the raw images.
- Directly modifies `AlbumGenerationJob` (`buildEventAlbums`/`buildLocationAlbums`), which today
  hardcodes `name = date string` / `name = "lat,lon"`.

## New data
None — same `album` table, just a smarter value written to its existing `name` column. Optionally
add `name_source` ENUM(`GENERATED`,`LLM`,`USER_EDITED`) so a user rename isn't silently overwritten
next time the job reruns.

## Backend design
- `domain/ai/AlbumNamingService`: given an album's date range, place (rounded GPS or, if feature 06
  adds reverse geocoding, a real place name), and top detected people/objects (from CLIP tags if
  available, else skip), build a compact prompt and call `OllamaTextService.complete(...)` →
  short title. On any failure/timeout, fall back to today's plain date/coordinate string — never
  block album generation on Ollama being unavailable.
- Modify `AlbumGenerationJob.buildEventAlbums()`/`buildLocationAlbums()` to call this service
  instead of inlining `name = ...`. Respect `name_source = USER_EDITED` by skipping regeneration
  for albums the user has manually renamed (check whether album rename UI exists yet; if not, this
  is a one-line guard for future-proofing, not blocking work now).

## UI
- No new screens. Album names simply look better wherever `AlbumRepository`-backed names are
  already rendered (library sidebar, discover cards, etc).

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (full file)
- `src/main/java/.../persistence/AlbumRepository.java`
- `00-shared-infrastructure.md` (§C — Ollama client/service)
- `06-places-youve-returned-to.md` (reverse-geocoding decision, if adopted)

**New:**
- `src/main/java/.../domain/ai/AlbumNamingService.java`
- Small migration adding `album.name_source` if the "don't overwrite user renames" guard is wanted
  now (`V019__album_name_source.sql`)
- Modifications to `AlbumGenerationJob.java`
