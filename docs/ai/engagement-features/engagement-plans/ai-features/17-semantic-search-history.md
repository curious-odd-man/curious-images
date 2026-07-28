# Semantic Search History

## What it does
Remember past searches (e.g. "dogs", "winter", "birthday", "red flowers") so the user can revisit
or get suggestions from them.

## Depends on shared infra
- Existing `domain/search/SearchService`, `SearchQueryParser`, `SearchAutocompleteManager` — this
  is a thin addition on top of the current search pipeline, not a new search engine.
- Optional light dependency on feature 16 (captions) if search history suggestions want to surface
  "searches similar to what your captions describe," but not required for the core feature.

## New data
- `search_history` table: `id`, `query_text`, `searched_at`, `result_count` — new migration
  `V021__search_history.sql`. Single-user desktop app, so no user_id column needed unless
  multi-profile support is planned.

## Backend design
- `persistence/SearchHistoryRepository`: `record(String query, int resultCount)`,
  `findRecent(limit)`, `findMostFrequent(limit)`.
- Hook into wherever `SearchService`/`event/model/SearchRequestedEvent` currently fires a search —
  record every executed query (debounced/on submit, not on every keystroke — check how
  `SearchAutocompleteManager` currently distinguishes "typing" from "search executed" before
  deciding the exact hook point).

## UI
- A dropdown/history panel under the existing search box (in whichever FXML currently hosts the
  search field — check `LibraryController`/`library.fxml` for the search bar's current location)
  showing recent + frequent past queries, clickable to re-run.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/search/SearchService.java`, `SearchQueryParser.java`,
  `SearchAutocompleteManager.java`
- `src/main/java/.../event/model/SearchRequestedEvent.java`
- `src/main/java/.../ui/controller/screen/LibraryController.java`, `resources/fxml/library.fxml`
  (search bar location)

**New:**
- `src/main/resources/db/migration/V021__search_history.sql`
- `src/main/java/.../persistence/SearchHistoryRepository.java`
- Modifications to `SearchService.java`/`LibraryController.java` to record history, and a small
  history dropdown UI addition to `library.fxml`
