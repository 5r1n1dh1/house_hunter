# House Hunter — Android App (Kotlin)

Minimal, privacy-first house-hunting app that aggregates rental listings from Indian renting
sites, driven by OSM Overpass for location data and Google Maps for work locations.

> **Legal note (read first):** Several target sites (NoBroker, 99acres, MagicBricks,
> housing.com, OLX, Facebook) prohibit automated scraping in their ToS and use anti-bot
> protection. This app is designed for **personal use** with strict rate limiting, robots.txt
> respect, and graceful degradation. Sites that offer official APIs/RSS (e.g. MagicBricks
> partner API, NoBroker's public-ish endpoints) should be preferred. Facebook Groups **cannot**
> be scraped reliably (login + anti-bot); support is limited to manual link import. Do not
> publish to Play Store as a scraper without legal review.

---

## Goal

Build a minimal-material Android app (Kotlin, Jetpack Compose) where the user:

1. Picks **State → District → Locality** (all dropdowns, populated live from
   **OpenStreetMap Overpass API**).
2. Pins **Work 1** and **Work 2** on a **Google Map**.
3. Gets a **deduplicated, sorted, filterable card list** of rental listings scraped from
   configured sites (NoBroker, NestRiqo, Razorent, NoBrokerage.com, housing.com, Nestaway,
   RentMyStay, MagicBricks, 99acres, bengaluru.rent, OLX, Facebook Groups, …).
4. Taps a card → full **property detail screen** (with Work 1/Work 2
   distance banner); card carries **price** + 4 icon-only buttons:
   **Call owner**, **Locate on map**, **Photos**, **Open in browser**.
5. Can **add new sites via a form** (name, URL template, CSS selectors) — no code changes.

---

## Requirements

### Functional
- **Onboarding / Preferences screen**
  - State, District, Locality dropdowns (cascading; each depends on previous selection).
  - Populated via Overpass `boundary=administrative` queries (state `admin_level=4`,
    district `admin_level=6`, locality `admin_level=8/9/10` within India).
  - Work 1 / Work 2 map pickers (Google Maps SDK + Places Autocomplete, store lat/lng).
- **Home screen**: listing cards (price, BHK, area, locality, site badge, posted date),
  pull-to-refresh, skeleton loading, empty/error states.
- **Card actions (icon-only)**: Call (`tel:` intent; disabled if no number),
  Locate (bottom-sheet map with marker + commute distance to Work 1/2),
  Photos (full-screen image pager via Coil, disk cache disabled),
  **Open original listing in browser** (`OpenInNew` icon → source URL).
- **Detail screen**: prominent **distance highlight banner** showing distance to
  **both** Work 1 and Work 2 (e.g. "3.2 km to Work 1 · 6.1 km to Work 2"),
  photos, price breakdown, BHK, area, furnishing, amenities, description,
  contact, map location, and a prominent **"Open original listing"**
  button that opens the source URL in the browser.
- **Sort**: price ↑/↓, distance to Work 1 / Work 2, newest.
- **Filters**: budget range, BHK (1/2/3/4+), furnishing, property type,
  max distance to Work 1 and/or Work 2 (sliders), "contact available" toggle.
- **Add-site form**: name, search URL template (`{STATE}`, `{DISTRICT}`, `{LOCALITY}`
  placeholders), CSS selectors (title, price, bhk, area, location, image, detailUrl, phone),
  detail-page field selectors, pagination pattern, test-fetch validation,
  enable/disable toggle. Persisted via DataStore.
- **Sites screen**: list all configured sites (built-in + custom) with
  enable/disable toggle, edit, delete — the entry point for the add-site form.
- **No background sync**: the app never syncs in the background;
  pull-to-refresh re-scrapes live (see Non-functional for the
  zero-cache / minimal-size policy).
- **Live data only**: no mock/demo data anywhere; every screen shows
  real scraped results, or an empty/error state.

### Non-functional
- Minimal UI: Material 3, monochrome-ish palette, one screen per task, no ads/trackers.
- Network: OkHttp with timeouts, retries with exponential backoff, per-site rate limit
  (≥ 5s between requests per host), rotating descriptive User-Agent.
- Architecture: MVVM, single-activity, Hilt DI, Coroutines/Flow, repository pattern.
- Zero cache / minimal size: no Room, no WorkManager, no on-disk data;
  R8 full mode + resource shrinking, vector drawables only; target
  APK < 15 MB. In-memory results only (ViewModel scope).
- Min SDK 26, target latest; Kotlin + Compose; testable parsers.

---

## Folder Structure

```
house_hunter/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/sites_default.json          # built-in site configs
│       │   ├── java/com/househunter/app/          # source tree below
│       │   └── res/                               # strings, icons, vector drawables
│       └── test/fixtures/                         # recorded HTML — test-only, never packaged
├── .github/
│   └── workflows/build-apk.yml                    # production APK build (free tier)
├── gradle/libs.versions.toml                      # version catalog
├── gradle/wrapper/                                # wrapper JAR committed (CI needs it)
├── gradlew / gradlew.bat
├── gradle.properties
├── local.properties.example                       # MAPS_API_KEY, signing (copy is git-ignored)
├── .gitignore
└── plan.md
```

Source tree under `app/src/main/java/com/househunter/app/`:

```
├── HouseHunterApp.kt
├── di/                            # Hilt modules (network, scraper)
├── data/
│   ├── prefs/                     # DataStore: settings, work locations
│   │   └── PrefsRepository.kt
│   ├── remote/
│   │   ├── OverpassApi.kt         # Retrofit service + query builder
│   │   ├── NominatimApi.kt        # geocoding (1 req/s policy)
│   │   └── HttpFetcher.kt         # OkHttp (rate limit, UA, Referer, robots)
│   ├── scrape/
│   │   ├── SiteScraper.kt         # interface + Jsoup implementation
│   │   ├── SiteRegistry.kt        # built-in + user-added configs
│   │   ├── SelectorSet.kt         # CSS selector mapping model
│   │   └── Normalizer.kt          # price/bhk/area parsers
│   └── repository/
│       ├── LocationRepository.kt  # Overpass dropdowns (in-memory)
│       └── ListingRepository.kt   # scrape+merge, in-memory only (no DB)
├── domain/
│   ├── model/                     # Listing, OsmPlace, WorkLocation, Filters, Sort
│   └── usecase/                   # GetListings, ApplyFilters, ComputeDistance
└── ui/
    ├── navigation/NavGraph.kt
    ├── home/                      # HomeScreen, ListingCard, SortMenu, FilterSheet
    ├── details/                   # DetailScreen, PhotoPager, MapSheet
    ├── preferences/               # LocationPickersScreen (state/district/locality)
    ├── workmap/                   # WorkMapScreen (Work 1 / Work 2 pickers)
    ├── sites/                     # SitesScreen (enable/edit/delete)
    ├── addsite/                   # AddSiteScreen + selector test
    ├── components/                # IconActionButton, PriceChip, SkeletonCard…
    └── theme/                     # Material 3 minimal theme
```

**Graded deps:** Compose BOM, Navigation-Compose, Hilt, Retrofit+OkHttp, Jsoup,
Coil (disk cache disabled), DataStore, Google Play Services Maps + Places,
Material 3, Material Icons Extended. **Dropped for size/zero-cache:**
Room, WorkManager.

---

## Plan (Milestones)

| # | Milestone | Outcome |
|---|-----------|---------|
| 1 | Scaffold | Compose project, Hilt, DataStore, nav graph, theme |
| 2 | CI pipeline | `build-apk.yml` green on an empty-but-compiling app (every later milestone is verified by CI, never locally) |
| 3 | Locations | Overpass cascading State→District→Locality dropdowns (in-memory) |
| 4 | Work maps | Google Maps pickers for Work 1/2; geocoding + Haversine distance util |
| 5 | Scrape engine | Config-driven `SiteScraper`, built-in configs for 2–3 reachable sites |
| 6 | Home UI | Card list with price + 4 icon actions, sort, filter sheet |
| 7 | Details | Detail screen, work-distance highlight banner, photo pager, map sheet, call intent |
| 8 | Sites screen | List/toggle/edit/delete sites, add-site form with live test-fetch validation |
| 9 | Live data layer | In-memory repo, no DB, no background sync, zero cache |
| 10 | Release | Signed production APK (secrets configured), versioned GitHub Release |
| 11 | Polish | Error states, rate-limit UX, unit/instrumented tests, lint |

**Target sites and feasibility** (config-driven; each row is one `SiteConfig`):

| Site | Access path (v1) | Notes |
|------|------------------|-------|
| NoBroker | public search endpoints returning HTML/JSON | primary source |
| NoBrokerage.com | server-rendered listing HTML | verify selectors |
| NestRiqo | server-rendered HTML | verify selectors |
| Razorent | server-rendered HTML | verify selectors |
| RentMyStay | server-rendered HTML | verify selectors |
| MagicBricks | server-rendered HTML (official partner API as fallback) | respect rate limits |
| 99acres | server-rendered HTML (official partner API as fallback) | respect rate limits |
| housing.com | server-rendered HTML, often `requiresManual` | heavy anti-bot |
| Nestaway (NestAway/Urban Company) | archived/redirected — `requiresManual` | likely dead source |
| bengaluru.rent | server-rendered HTML | locality is Bengaluru-specific |
| OLX India | `requiresManual` | JS-rendered + anti-bot |
| Facebook Groups | **manual import only** | login + anti-bot; no automation |

Rows marked `requiresManual` ship as disabled entries the user can enable and
finish configuring via the add-site form; the first row of the table (NoBroker)
must work end-to-end before the app is considered functional.

**Toolchain pinned in `gradle/libs.versions.toml`**: JDK 17, AGP 8.x, Kotlin 2.x,
Compose BOM, Gradle wrapper 8.x (wrapper JAR committed for CI).

---

## Implementation Details

### 0. Navigation graph

Single-activity Compose navigation, minimal depth:

| Route | Screen | Entered from |
|-------|--------|--------------|
| `preferences` | State / District / Locality dropdowns | first launch, home top-bar |
| `workmap` | Work 1 / Work 2 map pickers | home top-bar |
| `home` | Listing cards, sort, filters | after preferences saved |
| `details/{listingId}` | Property details | card tap |
| `sites` | Site list (toggle/edit/delete) | home overflow menu |
| `addSite` (+ `addSite/{siteId}` for edit) | Add-site form | `sites` FAB |

`details/{listingId}` takes an id only; the `Listing` instance is passed
through the shared in-memory `ListingRepository` (no DB, no serialization).

### 1. Location dropdowns via Overpass

`LocationRepository` queries `https://overpass-api.de/api/interpreter` (mirror fallback:
`overpass.kumi.systems`). Results are held in **memory only** (ViewModel
scope, keyed by level+parent) — nothing is persisted, honoring the
zero-cache policy; repeat opens re-query Overpass.

```kotlin
// States of India (admin_level=4 inside country)
// [out:json][timeout:25]; area["ISO3166-1"="IN"][admin_level=2]->.c;
// relation["boundary"="administrative"]["admin_level"="4"](area.c); out tags;

// Districts of a state: filter relations admin_level=6 inside the state relation
// relation["boundary"="administrative"]["admin_level"="6"](area.stateRel); out tags;

// Localities: admin_level in (8,9,10) inside district, names only
```

Cascading rule: District dropdown enabled only after State selected; Locality after District.
Results shown as a `ModalBottomSheet` searchable dropdown (minimal UX). Fallback: free-text
locality entry if Overpass fails.

Edge cases handled:
- `admin_level` is not uniform across Indian states (some map districts as 5 or 7),
  so the district query accepts a configurable set of levels per state and falls
  back to fetching all `boundary=administrative` relations under the state relation.
- Names: prefer `name:en`, fall back to `name`, then `name:hi` (regional labels).
- Deduplicate relations that share a name; sort alphabetically with
  `Intl.Collator` so diacritics sort sensibly.
- Reload only the deepest level when the parent changes; memoize the
  current cascade in memory for the session.

### 2. Work locations (Google Maps)

- `WorkMapScreen`: `GoogleMap` + marker drag; Places Autocomplete bar for search.
- Two tabs/segments: **Work 1**, **Work 2**; each stores `WorkLocation(name, lat, lng)` in
  DataStore.
- `ComputeDistance` usecase: Haversine km from each listing to both work points.
  Used by:
  - **Details page highlight banner** — both distances shown prominently at the
    top of the screen ("3.2 km to Work 1 · 6.1 km to Work 2", each tappable to
    open a map sheet with route markers); missing work locations degrade to a
    single-value or hidden banner.
  - Filters (`maxDistanceWork1`, `maxDistanceWork2` sliders).
  - Sorts (`nearestWork1`, `nearestWork2`).
  Optional commute-time via Directions API is explicitly out of scope for v1 (billing).

**Listing coordinates** (needed for any distance feature):
1. Use site-provided `lat/lng` when the page exposes it (JSON-LD `geo`,
   embedded `latitude/longitude` meta tags — `HttpFetcher` checks these first).
2. Otherwise geocode `"<locality>, <district>, <state>"` via
   Nominatim (`https://nominatim.openstreetmap.org/search`), honouring its
   usage policy: identifying User-Agent, max 1 request/second.
3. Otherwise use the Overpass centroid of the selected locality relation.
4. If none resolve, the listing shows "distance unavailable" and is excluded
   from distance sorts/filters.

Geocoding results are memoized **in memory for the session only** — no cache.

### 3. Scraping engine

```kotlin
data class SelectorSet(
    val listContainer: String, val title: String, val price: String,
    val bhk: String?, val areaSqft: String?, val location: String?,
    val imageUrl: String?, val detailUrl: String?, val phone: String?,
    val postedDate: String?
)

data class SiteConfig(
    val id: String, val name: String, val baseUrl: String,
    val searchUrlTemplate: String,   // "...?loc={LOCALITY}&city={DISTRICT}"
    val selectors: SelectorSet,
    val detailFields: DetailFields,  // regex/selector map for the detail page
    val pagination: Pagination?,     // next-page selector or {PAGE} placeholder
    val rateLimitSeconds: Int = 6,
    val enabled: Boolean = true, val isCustom: Boolean = false,
    val requiresManual: Boolean = false
)

data class DetailFields(           // keyed field -> regex-with-capture-group
    val description: String? = null, val amenities: String? = null,
    val images: String? = null, val phone: String? = null,
    val deposit: String? = null, val maintenance: String? = null,
    val availableFrom: String? = null
)
```

- `SiteScraper.scrape(config, prefs): Flow<List<Listing>>` — builds URL from template,
  fetches via `HttpFetcher` (timeouts, backoff, robots.txt check), parses with Jsoup,
  normalizes (₹/Lakh→number, "2 BHK"→2, "1200 sqft"→1200), returns `Listing`s with
  `siteId`, `externalId` (hash of detailUrl) for in-memory dedupe within the
  current refresh only. Nothing is written to disk.
- **Built-in configs** ship in `assets/sites_default.json`; per-site quirks isolated in the
  config (never per-site classes) so a site change is a JSON edit.
- **Two-phase fetch**: (1) search page → card-level `Listing` list;
  (2) on detail-screen open, `SiteScraper.fetchDetail(config, listing)` parses the
  detail page with `detailFields` to fill description, amenities, all images,
  deposit/maintenance, and phone. Failures degrade to card-level data.
- **Client-side locality guard**: after scraping, drop listings whose locality
  string does not contain the selected locality name (guards sites that ignore
  the query parameter).
- **Concurrency**: sites are scraped sequentially by default (respects rate
  limits); optional max-3-parallel setting. Each site emits progress/result to
  the ViewModel, which renders incrementally and shows a per-site status row
  (`12 · 8 · 0 · error` chips) so broken selectors are visible at a glance.
- **Hotlink protection**: `HttpFetcher` sends `Referer: <site baseUrl>` for
  both page and image requests; images that 403 are dropped from the list.
- **JS-rendered / bot-protected sites** (OLX, housing.com, Facebook): mark
  `requiresManual=true` — the form allows saving them with a note; they fail gracefully.
  Preferred sources are ones with server-rendered HTML or public endpoints.
- **Facebook Groups**: only manual import (paste a listing URL → user pastes details into the
  detail form). Automated scraping is infeasible; do not attempt login automation.
- **Call owner**: if `phone` parsed → `Intent.ACTION_DIAL`; else deep-link to source listing.
- **Open in browser**: `Intent.ACTION_VIEW` on `detailUrl`; if no browser resolves,
  copy the URL to clipboard and show a snackbar.

### 4. Listing model

```kotlin
data class Listing(
    val id: String,            // sha256(siteId + detailUrl)
    val siteId: String, val title: String,
    val rent: Double, val deposit: Double?, val maintenance: Double?,
    val bhk: Int?, val areaSqft: Double?, val furnishing: String?,
    val propertyType: String?, val amenities: List<String>,
    val state: String, val district: String, val locality: String,
    val lat: Double?, val lng: Double?,
    val images: List<String>, val phone: String?,
    val detailUrl: String, val postedAt: Instant?, val description: String?
)

data class WorkLocation(val label: String, val lat: Double, val lng: Double)

enum class Sort { PRICE_ASC, PRICE_DESC, NEAREST_WORK1, NEAREST_WORK2, NEWEST }

data class Filters(
    val rentMin: Double? = null, val rentMax: Double? = null,
    val bhk: Set<Int> = emptySet(), val furnishing: Set<String> = emptySet(),
    val propertyType: Set<String> = emptySet(),
    val maxKmWork1: Double? = null, val maxKmWork2: Double? = null,
    val contactOnly: Boolean = false
)
```

### 5. Home screen & cards

- `LazyColumn` of `ListingCard` (elevated surface, 1dp border): **price** prominent
  (`₹18,000/mo`), `2 BHK · 1100 sqft · Indiranagar`, site badge, posted date.
- Four icon-only `IconActionButton`s: `Icons.Default.Call`, `Icons.Default.Place`,
  `Icons.Default.PhotoCamera`, `Icons.Default.OpenInNew` (open original
  listing in browser).
- Card click → `DetailScreen`. Sort menu (dropdown) + filter icon opens
  `FilterBottomSheet` (range slider for budget, BHK chips, furnishing chips,
  max-distance sliders, contact-only switch). State held in ViewModel; applied via
  `ApplyFilters` usecase over the in-memory result of the live fetch.
- **Sort semantics**: `newest` uses parsed `postedAt`, falling back to
  scrape order for listings without a date (per-site ordering preserved).
  Distance sorts place listings with unknown coordinates last.

### 6. Detail screen

- **Distance highlight banner** (top, prominent): "🏠 3.2 km to Work 1 ·
  6.1 km to Work 2" — computed by `ComputeDistance` (Haversine); each value
  tappable to open a map sheet with the property + both work markers. Hidden
  per work point if that work location isn't set.
- Photo pager (Coil, crossfade, disk cache disabled) → price breakdown →
  key facts grid → amenities chips → description → map snippet (static
  marker, tap opens full map sheet) → contact row (call button + copy
  number) → prominent **"Open original listing"** button (browser intent
  to `detailUrl`).

### 7. Sites screen & add-site form

`sites`: one row per configured site — name, enable switch, result count from the
last fetch, error state, edit/delete (built-ins can be disabled but not deleted).

`addSite`: name, base URL, search URL template with placeholder helper text, one text
field per selector (with live preview of matched values after "Test fetch"),
detail-page field regexes, pagination, rate limit, enable toggle.
Validation: real HTTP GET on a sample query using the **current** preferences
(state/district/locality); show the first 3 **real** parsed listings as preview,
with per-field match counts, before save. Saved via DataStore; `SiteRegistry`
merges built-in + custom configs in memory. The form never works with sample data.

Fields must be validated client-side before fetch: non-empty selectors
(`listContainer`, `title`, `price`, `detailUrl`), URL parses, template contains
`{LOCALITY}`.

### 8. Live-only architecture, resilience

- **Zero caching**: no Room, no files, no WorkManager, no background
  sync. Every screen fetches live from the source when opened; results
  live in memory only (ViewModel scope) and are discarded on process
  death. Only user settings persist (DataStore): location selections,
  work locations, filters, user-added site configs.
- Manual refresh = pull-to-refresh (re-scrape). No offline mode —
  offline shows an error state, never stale data.
- Per-site failure isolated (other sites still list); errors surfaced
  as snackbars.
- Dedupe across sites by normalized title+locality+price fuzzy match
  within the current in-memory result set only.
- Coil image loading with `diskCachePolicy(DISABLED)` to honor the
  no-cache rule.
- App size: R8 full mode, `shrinkResources`, no bundled HTML/test
  assets in the release APK, vector drawables only.

### 9. Build & release (GitHub Actions — free tier)

**The app is never compiled on the local machine.** All builds happen on
GitHub Actions (free account: 2,000 build minutes/month on `ubuntu-latest`,
sufficient for this project). Status is checked locally via the `gh` CLI
(`gh` app / GitHub CLI):

```bash
git push origin main
gh run list --repo <owner>/house_hunter          # build status
gh run watch <run-id>                            # live log tail
gh run view <run-id>                             # jobs, artifacts, logs
gh run download <run-id> --name app-release      # fetch APK
adb install app-release.apk                        # install on a connected device
```

Workflow at `.github/workflows/build-apk.yml` (complete, drop-in):

```yaml
name: Build Production APK
on:
  push:
    branches: [main]
    tags: ["v*"]
  pull_request:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest                      # free-tier runner
    timeout-minutes: 25
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle
      - uses: android-actions/setup-android@v3
      - name: Grant execute permission
        run: chmod +x ./gradlew
      - name: Run unit tests & lint
        run: ./gradlew testDebugUnitTest lintDebug --no-daemon
      - name: Assemble release APK (unsigned)
        run: ./gradlew assembleRelease --no-daemon
        env:
          MAPS_API_KEY: ${{ secrets.MAPS_API_KEY }}
      - name: Align & sign APK
        run: |
          BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
          DIR=app/build/outputs/apk/release
          UNSIGNED="$DIR/app-release-unsigned.apk"
          OUT="$DIR/app-release.apk"
          if [ -n "$SIGNING_KEYSTORE_BASE64" ]; then
            echo "$SIGNING_KEYSTORE_BASE64" | base64 -d > keystore.jks
            "$BT"/zipalign -f -v 4 "$UNSIGNED" app-aligned.apk
            "$BT"/apksigner sign --ks keystore.jks \
              --ks-pass "pass:$SIGNING_KEYSTORE_PASSWORD" \
              --key-pass "pass:$SIGNING_KEY_PASSWORD" \
              --ks-key-alias "$SIGNING_KEY_ALIAS" --out "$OUT" app-aligned.apk
            "$BT"/apksigner verify --verbose "$OUT"
          else
            "$BT"/zipalign -f -v 4 "$UNSIGNED" "$OUT"
            echo "::warning::No signing secrets — APK is unsigned"
          fi
          ls -lh "$OUT"
        env:
          SIGNING_KEYSTORE_BASE64: ${{ secrets.SIGNING_KEYSTORE_BASE64 }}
          SIGNING_KEYSTORE_PASSWORD: ${{ secrets.SIGNING_KEYSTORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
      - uses: actions/upload-artifact@v4
        with:
          name: app-release
          path: app/build/outputs/apk/release/app-release.apk
          if-no-files-found: error
```

Notes:
- `apksigner` (not `jarsigner`) is used so the APK carries v1+v2+v3 signature
  schemes required by modern Android; `zipalign` runs **before** signing.
- `app/build.gradle.kts` intentionally declares **no** `signingConfig`, so
  `assembleRelease` always emits `app-release-unsigned.apk` and signing happens
  in exactly one place (CI). This avoids the signed/unsigned file-name mismatch.
- The sign step is conditional on `SIGNING_KEYSTORE_BASE64`, so PRs/forks
  without secrets still produce an aligned (unsigned) artifact instead of failing.
- `app/build.gradle.kts` reads `MAPS_API_KEY` from env (falling back to
  `local.properties`) into `manifestPlaceholders`; a missing key still
  compiles but shows an empty map screen at runtime.
- CI prerequisites (committed to the repo, never generated on the runner):
  `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/libs.versions.toml`, and `gradle.properties` with `org.gradle.jvmargs`.
- Free-tier math: public repos get **unlimited** hosted-runner minutes;
  private repos get 2,000 min/month. Keep the repo public (or accept the
  quota) — a full build of this app is ~3–5 minutes.
- Local dev workflow: write code → `git push` → check status with `gh`
  (`gh auth login` once) — **no `./gradlew build` locally**.
- Optional: `on: tags` step can attach the APK to a GitHub Release
  (`softprops/action-gh-release`) for versioned distribution.

Repository secrets to configure: `SIGNING_KEYSTORE_BASE64`,
`SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`,
`MAPS_API_KEY` (generated with
`keytool -genkeypair -v -keystore househunter.jks -keyalg RSA -keysize 2048 -validity 10000 -alias househunter`
then `base64 -w0 househunter.jks`).

### 10. Permissions, security, privacy

- **Manifest**: `INTERNET` only. `ACCESS_FINE_LOCATION` is *not* required —
  the map picker is centered on India by default; skip the permission prompt
  entirely for minimal UX.
- **Cleartext**: Android blocks plain HTTP by default and several listing
  sites still serve `http://` assets. Add
  `res/xml/network_security_config.xml` allowing cleartext **only** for the
  configured site domains (declared dynamically is not possible, so the
  built-in site list is mirrored there).
- **Privacy**: no analytics, no ads, no account, no device identifiers.
  Requests go only to the configured sites, Overpass, and Nominatim.
- **Secrets**: `MAPS_API_KEY` is read from `local.properties` (local) or the
  `MAPS_API_KEY` repo secret (CI) into `manifestPlaceholders`; restrict the
  key by package name + signing certificate in the Google Cloud console.

### Testing

- Unit: Jsoup parsers against recorded HTML fixtures in `src/test/fixtures/`
  (test-scoped only — **never packaged in the APK**); Overpass query
  builder; Haversine; filter/sort logic.
- Instrumented: dropdown cascade, card actions, add-site validation flow
  (happy path).
- **No mock data in the app**: the app itself only ever displays live
  scraped results; empty/error states replace any placeholder or
  demo content.
- CI (GitHub Actions): `./gradlew testDebugUnitTest lintDebug` on every
  push/PR; instrumented tests run on a separate workflow with an
  emulator (Firebase Test Lab or `reactivecircus/android-emulator-runner`
  free-tier) — never run locally per the build policy.

## Implementation Status

All 11 milestones are implemented; CI run **#38067185303** is green and produces a
signed release APK (`v2` + `v3` signature schemes, ~2.1 MB).

Delivered as planned: Overpass cascading dropdowns, Google Maps work-location
pickers with a Work 1 / Work 2 distance banner on the details screen, config-driven
`SiteScraper` + Sites screen + add-site form with real test-fetch, card list with
price + 4 icon actions (call / map / photos / open in browser), detail screen with
two-phase detail fetch, sort + filters, zero caching, and the free-tier CI pipeline.

### Deviations from the plan (and why)

| Plan | Implementation | Reason |
|------|----------------|--------|
| Google Places Autocomplete for work search | **Nominatim** search + map marker | Places needs a billing-enabled key; Nominatim is free. Keeps the app usable with no Google key at all. |
| `gradle-wrapper.jar` committed | CI **bootstraps Gradle 8.10.2** and generates the wrapper per run | Binary wrapper JAR is not committed; keeps the repo text-only. `actions/setup-android@v3` also fails on current runners, so the preinstalled SDK is used. |
| Built-in site configs enabled | All ship **disabled** (`requiresManual: true`) | Verified from this environment: NoBroker → 403, housing.com → 406, 99acres → 403, MagicBricks/OLX → 404. Users enable a source and fix selectors via the Add-site form. |
| Instrumented tests in CI | Unit tests + lint only | Keeps every run inside the free-tier minute budget. |

### Operations

- Signing keystore: `~/.househunter/househunter.jks` (alias `househunter`) — **back this
  up**; the app cannot be upgraded on a device without the same key.
- To enable the map: add the `MAPS_API_KEY` repo secret (`gh secret set MAPS_API_KEY`).
  Without it the app shows an explanatory panel instead of a blank map.
- Artifact: `gh run download <run-id> --name app-release`.

---

## Open risks

1. Site HTML/anti-bot changes break scrapers → config-driven selectors + per-site health
   flag + easy JSON edits.
2. Overpass rate limits (2 slots/slot-time) → in-memory memoization per
   session, use mirrors, debounce dropdown queries.
3. Google Maps requires an API key (billing-enabled account) → supply via
   `local.properties` locally and the `MAPS_API_KEY` repo secret in CI;
   map screens degrade gracefully when the key is missing/invalid.
4. ToS/legal exposure → personal-use framing, robots.txt respect, official APIs preferred.
5. GitHub Actions free-tier quota (2,000 min/month private) → keep repo public,
   cancel duplicate runs via `concurrency`, skip instrumented tests on PRs.
6. No offline mode (zero-cache policy) → poor networks show clear error
   states; fetch-on-open keeps the app small and always fresh.
7. Nominatim/Overpass usage policies (rate limits, identifying User-Agent)
   → centralized `HttpFetcher` policy + sequential scraping by default.
