# Ask Your Archive

## What it does
Natural-language Q&A over the whole library via a local LLM, e.g. "Show all times Alice and Bob
were together," "When did I last visit Paris?", "Show snowy mountains."

## Depends on shared infra
- §C (`OllamaTextService`, and the "streaming chat" variant flagged as a later enhancement in §C —
  this is the feature that actually wants token streaming for a responsive chat UI, unlike the
  batch jobs elsewhere).
- §E (`ClipVectorIndex`/`SearchService`) for retrieval — "snowy mountains" is a pure CLIP text→image
  query, no LLM needed at all for that class of question.
- Person clustering (`PersonRepository`/`FaceRepository`) for "Alice and Bob together" queries.
- §D (GPS) for "when did I last visit Paris" style queries (again, needs the reverse-geocoding
  decision from feature 06 to map "Paris" → a coordinate region, or a simpler v1 that only
  understands place queries the user has already named via existing `LOCATION`/`RETURN_VISIT`
  albums/cards).

## Architecture: retrieval + light routing, not open-ended RAG
This should **not** be "dump the whole library into an LLM prompt." Structure it as:
1. **Intent routing** (cheap, can even be rule-based rather than LLM-based for v1): classify the
   query into one of a few handled shapes — (a) semantic visual search ("snowy mountains") → pure
   CLIP text→image via existing `SearchService`; (b) co-occurrence ("Alice and Bob together") →
   structured SQL query over `face`/`person`; (c) place+time ("last visit to Paris") → structured
   query over `media`/GPS/albums; (d) anything else → fallback: CLIP semantic search only.
2. **Structured retrieval** using the appropriate existing repository/service for the routed
   intent — never let the LLM invent an answer about *what's in the library*; it only ever
   summarizes/phrases results that a real query already fetched.
3. **Optional LLM synthesis** (`OllamaTextService`): turn the structured result set into a
   conversational answer ("You last visited Paris in June 2022 — here are 12 photos"), with the
   actual photos always shown as real query results, not LLM-invented facts.
- A true open-vocabulary LLM query router (using the LLM itself to decide intent/parameters) is a
  reasonable v2 once v1's structured routing is validated — flag it as a follow-up rather than
  building it first.

## New data
None beyond what other features already add (search_history from feature 17 can double as
chat history if desired, or add a dedicated `archive_chat_message` table if a persistent
multi-turn conversation view is wanted).

## Backend design
- `domain/archive/ArchiveQueryRouter`: classifies the query (rule-based keyword/pattern matching
  is sufficient for v1 — "together", "and", person names present → co-occurrence; place names /
  "visit" → place+time; else → visual search).
- `domain/archive/PersonCooccurrenceQueryService`: given two+ person ids/names, query `face`
  joined by `media_id` for photos containing all of them.
- `domain/archive/PlaceTimeQueryService`: given a place (resolved via feature 06's naming, or a
  known album/card name the user references), find matching media ordered by date.
- `domain/archive/ArchiveChatService`: orchestrates router → retrieval service → optional
  `OllamaTextService` phrasing → returns `(answerText, List<Media>)` to the UI.

## UI
- A dedicated "Ask" chat panel/screen (`FxmlView.ARCHIVE_CHAT` → `archive_chat.fxml`/
  `ArchiveChatController`), simple message list + text input, each answer rendering both prose and
  a strip of matching photo thumbnails (reuse `GridCellController`/`photo_cell.fxml` cells inline).

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/search/SearchService.java`
- `src/main/java/.../persistence/FaceRepository.java`, `PersonRepository.java`
- `src/main/java/.../persistence/MediaRepository.java`
- `16-describe-this-photo.md` (shared Ollama module, build that module first)
- `06-places-youve-returned-to.md` (place-name resolution dependency)
- `00-shared-infrastructure.md` (§C, §E)

**New:**
- `src/main/java/.../domain/archive/ArchiveQueryRouter.java`
- `src/main/java/.../domain/archive/PersonCooccurrenceQueryService.java`
- `src/main/java/.../domain/archive/PlaceTimeQueryService.java`
- `src/main/java/.../domain/archive/ArchiveChatService.java`
- `src/main/resources/fxml/archive_chat.fxml` + `ArchiveChatController.java`
- `FxmlView.ARCHIVE_CHAT` registry entry
