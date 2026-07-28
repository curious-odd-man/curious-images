# Shared Infrastructure for Engagement Features

Read this before any individual feature plan. It describes the handful of subsystems that most
of the 24 features build on. Building these once, well, is what keeps the per-feature work small.
Every feature doc below links back to the specific section(s) it depends on instead of repeating
this design.

Grounding notes about the current codebase (verified from `main.zip`):

* JavaFX desktop app, Spring (non-Boot) DI, PostgreSQL via jOOQ, Flyway migrations under
  `src/main/resources/db/migration`.
* CLIP ViT-B/32 image+text encoders (`domain/ai/ClipImageEncoder`, `ClipTextEncoder`) already
  produce L2-normalised 512-dim embeddings for every photo/video frame, stored in Postgres
  (`clip_embedding` table) **and** mirrored into a Lucene HNSW index (`domain/index/ClipVectorIndex`)
  for fast KNN search. Reuse this — do not build a second vector store.
* Face pipeline: RetinaFace detection → ArcFace embedding → `PersonClusteringService` groups faces
  into `person`/`cluster` rows. Reuse `PersonService`/`PersonRepository`/`ClusterRepository`.
* GPS lat/lon/altitude are already extracted on import and live on `MediaPhotoRecord`/`MediaVideoRecord`
  (exposed via the `Media` facade: `getGpsLat()`, `getGpsLon()`).
* `domain/ai/AlbumGenerationJob` already builds `PERSON`, `EVENT` (time-gap clustering) and
  `LOCATION` (GPS grid-cell, ~1km) albums nightly/on-demand. Several new features are refinements
  of logic that already exists here — factor it out rather than duplicating it (see §D).
* Background work runs through `util/async/jobs/JobManager` + `BackgroundJob` (queue, dedup by
  class, progress events). All new offline computation should be a `BackgroundJob` submitted the
  same way `AlbumGenerationJob`/`ThumbnailGenerationJob`/`DuplicateDetectionJob` are.
* Cross-cutting notifications go through the Spring `ApplicationEventPublisher` +
  `event/model/*Event` + `NotificationsService`/`ui/controller/custom/NotificationMenuItemController`.
* Navigation is a flat registry: `ui/FxmlView` (record constants pairing an FXML path with a
  controller class) + `config/StageManager#switchScene`. There is currently **no dashboard/home
  screen** — `FxmlView.LIBRARY` (`LibraryController`) is the app's landing view, a photo grid with
  a tree sidebar (`TreeManager`) and a right detail panel (`RightPanelController`).
* No local LLM/VLM exists yet. The user has **Ollama** running locally — this plan integrates
  against Ollama's REST API rather than embedding a model runtime directly.

---

## A. New "Discover" home surface

Several features (On This Day, Today I Found, Rediscover Forgotten Photos, Random Memory, Mystery
Photo, This Never Happened Again, Archive Explorer, Hidden Patterns, AI-generated Stories,
Statistics) are all fundamentally "generate a card/screen and let the user open it from somewhere
central." Rather than bolting seven independent entry points onto `LibraryController`, add one
new landing surface:

* **New screen**: `FxmlView.DISCOVER` → `discover.fxml` / `DiscoverController`, reachable via a new
  toolbar/sidebar entry in `library.fxml` (a "Discover" icon button, analogous to the existing
  Settings/Duplicates buttons already in that toolbar).
* Layout: a card carousel/feed (JavaFX `ScrollPane` + `HBox`/`FlowPane` of card nodes, one FXML
  cell per card type, similar in spirit to `photo_cell.fxml` + `GridCellController`) plus a
  left-hand list of "sections" (On This Day / Monthly Story / Stats / Explorer feed).
* Each card type gets its own small FXML partial + controller (`OnThisDayCardController`,
  `InsightCardController`, etc.), all implementing a common `DiscoverCard` marker so
  `DiscoverController` can render a heterogeneous feed without a big switch statement everywhere
  (a `Map<CardType, Callback<Node,?>>` factory registry is enough — no need for a generic
  interface hierarchy beyond that).
* A lightweight **"Today I Found" launch popup/banner** can reuse the existing
  `NotificationsService`/`UserNotificationEvent` plumbing instead of being part of `Discover` — see
  feature doc for exact placement.

## B. Insight Card model (the core shared data structure)

Most "one interesting fact per launch" style features (Today I Found, Hidden Patterns, This Never
Happened Again, Archive Explorer, Mystery Photo, Random Memory) are really the same shape of
object: *a precomputed, typed, ranked nugget pointing at 1-N media rows*. Model this once:

**New table** `insight_card`:
- `id` PK
- `type` (varchar; e.g. `ON_THIS_DAY`, `RETURN_VISIT`, `RARE_SUBJECT`, `THEN_VS_NOW`,
  `FIRST_OF_SUBJECT`, `STAT_PATTERN`, `SEMANTIC_DISCOVERY`, `SCREENSHOT_CLUSTER`, ...)
- `title`, `subtitle` (generated text)
- `payload` (jsonb — type-specific structured data, e.g. `{"person_id":..., "years":[...]}`)
- `primary_media_id`, and a join table `insight_card_media(card_id, media_id, position)` for
  cards that reference several photos (reuses the same pattern as `album_photo`)
- `score` (double — for ranking/selection, e.g. recency + rarity + diversity heuristics)
- `generated_at`, `expires_at` (nullable — some cards are date-bound, e.g. On This Day)
- `dismissed_at`, `shown_count`, `last_shown_at` (so the same card doesn't repeat every launch)

**New classes**:
- `dbobj` additions via a new Flyway migration (`V013__insight_cards.sql`) + regenerated jOOQ
  sources (this repo generates jOOQ records from migrations — follow the existing pattern used
  for `album`/`cluster` etc.)
- `persistence/InsightCardRepository` (CRUD + `findActiveByType`, `findNextUnshown`, `markShown`,
  `dismiss`)
- `domain/insights/InsightGenerator` — a small interface each feature's generator implements:
  `List<InsightCandidate> generate()`. Candidates get scored/deduped centrally.
- `domain/insights/InsightGenerationJob` — one `BackgroundJob` that runs all registered
  `InsightGenerator` beans (Spring picks them all up via `List<InsightGenerator>` injection),
  replaces stale cards of each type, and publishes `AiPipelineCompleteEvent`-style completion.
  Scheduled the same way `AlbumGenerationJob` is (after import/AI pipeline finishes, plus a daily
  timer — see `util/async/jobs/JobManager` for how recurring submission is currently done, e.g.
  from a startup runnable or a `ScheduledExecutorService` if none exists yet).
- `domain/insights/InsightRankingService` — picks which card(s) to surface next (weighted random
  among unshown/least-recently-shown, respecting `dismissed_at`).

Individual feature docs below only describe **their specific `InsightGenerator` implementation**
and reuse this table/job/UI plumbing — that's the point of factoring it out here.

## C. Ollama integration (local LLM/VLM)

Ollama exposes a REST API on `http://localhost:11434` (`/api/generate`, `/api/chat`,
`/api/embeddings`), and accepts base64 images in `images: []` for multimodal models.

**New module** `domain/ollama/`:
- `OllamaConfig` — base URL, text model name, vision model name, timeout, enabled flag; persisted
  via the existing `UserPreferencesService`/`UserPrefKey` mechanism (same pattern as `AiConfig`/
  `AiSettingsService`) so the user can pick models from Settings without a rebuild.
- `OllamaClient` — thin wrapper around `java.net.http.HttpClient` posting to `/api/generate` (or
  `/api/chat`) with `"stream": false` for simplicity (JavaFX doesn't need token streaming for
  batch/offline jobs; streaming can be added later for the interactive "Ask your archive" chat box).
- `OllamaTextService` — `String complete(String systemPrompt, String userPrompt)`, used by
  narrative/naming features.
- `OllamaVisionService` — `String describe(byte[] jpegBytes, String prompt)`, used by "Describe
  this photo".
- Model recommendation, given this is a **bulk offline batch job over a whole photo library**
  (thousands of images) running on a local machine via Ollama:
  - **Vision/captioning**: `moondream` (1.8B) — by far the fastest/lightest VLM Ollama serves;
    good enough for short factual captions at library scale. `llava:7b` or `qwen2.5vl:7b` are
    better quality but far slower for a full-library batch job; offer them as an alternative in
    Settings, default to `moondream`.
  - **Text/narrative synthesis** (album titles, event names, monthly/yearly recap prose, RAG
    answer synthesis): `llama3.2:3b` for speed on modest hardware, with `qwen2.5:7b-instruct` as
    the "better quality, slower" option. Both are commonly pulled Ollama models; do not hardcode
    one — read the model name from `OllamaConfig` and let Settings show whatever
    `GET /api/tags` reports as already pulled.
  - Treat all LLM output as **best-effort enrichment**: catch failures, degrade to a
    non-LLM fallback string (e.g. plain date range instead of a generated title), never block the
    rest of the pipeline on Ollama being unreachable.
- **Settings UI**: add an "AI / Ollama" section to `settings.fxml`/`SettingsController` — base URL,
  model dropdowns populated from `/api/tags`, a "Test connection" button, and an enable/disable
  toggle per feature group (captioning vs narrative vs chat) since some users may not want an LLM
  running at all.

Any feature below that says "needs Ollama" depends on this section.

## D. Geo-clustering extraction

`AlbumGenerationJob.buildLocationAlbums()` already buckets photos into ~1km GPS grid cells. Pull
this into a standalone `domain/geo/GeoClusteringService` with a method like
`Map<GeoCell, List<Media>> clusterByProximity(List<Media>, double cellSizeDegrees)`, parameterised
so callers can ask for coarser/finer cells (e.g. "same beach" vs "same country"). Both the existing
`AlbumGenerationJob` and the new "Places you've returned to" / Statistics travel-heatmap /
Monthly-Story location summaries call this one implementation instead of three copies of the
rounding logic.

## E. Reused vector/search infra

- `domain/index/ClipVectorIndex` + `ClipEmbeddingRepository` — reused as-is for outlier detection
  (This Never Happened Again), retrieval for Ask Your Archive, and similarity grouping (AI album
  suggestions). No new embedding store needed.
- `domain/search/SearchService` / `SearchQueryParser` — existing text→CLIP semantic search. Extend
  minimally (see Semantic Search History) rather than rebuilt.
- `domain/dedupe/*` (perceptual hashing: `PixelHasher`, `PcaHasher`, `InvariantHasher`) — reused
  with a looser similarity threshold for "Similar cleanup", instead of introducing a second
  near-duplicate algorithm.

## F. Naming/versioning convention for this plan set

Each feature file below lists:
1. **What it does** (one paragraph, tied to the source doc's description).
2. **Depends on** (which of A–E above it needs).
3. **New data** (schema/tables beyond what §B/§D/§E already added, if any).
4. **Backend design** (new classes/packages, how it plugs into the shared job/generator model).
5. **UI** (which screen/card it renders in).
6. **Files to hand to the implementing AI** — both *existing* files it must read for context and
   *new* files it will create. Paths are relative to the repo root (`src/main/java/...`,
   `src/main/resources/...`).
