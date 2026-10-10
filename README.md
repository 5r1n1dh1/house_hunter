# House Hunter

Minimal, privacy-first Android app that aggregates rental listings from Indian
property sites, uses **OpenStreetMap Overpass** for the State / District /
Locality dropdowns and **Google Maps** for Work 1 / Work 2 locations.

## What it does

- Cascading **State → District → Locality** dropdowns populated from Overpass.
- Pin **Work 1** and **Work 2** on a map; every listing shows the distance to
  **both** on the details screen (highlighted banner) and can be sorted /
  filtered by it.
- Scrape listings from configurable sites and show them as minimal cards with
  price + 4 icon buttons: **call owner**, **locate on map**, **photos**,
  **open original listing in browser**.
- Tap a card for full details (description, amenities, all photos, deposit,
  maintenance, contact).
- **Add your own sites** with a form (search URL template + CSS selectors) and a
  live "Test fetch" against the real site.
- **Zero caching**: nothing is stored but your own settings. Every screen fetches
  live data; no database, no background sync.

## Build

The app is **never compiled locally** — builds run on GitHub Actions:

```bash
git push origin main
gh run list                 # status
gh run watch <run-id>       # live logs
gh run download <run-id> --name app-release   # fetch the APK
```

The workflow bootstraps Gradle itself (the wrapper JAR is not committed),
runs unit tests + lint, then produces a release APK. Signing is optional and
driven by the `SIGNING_*` repository secrets; without them you get an aligned
(but unsigned) APK that still installs for testing with `adb install`.

### Repository secrets (optional)

| Secret | Purpose |
|--------|---------|
| `MAPS_API_KEY` | Google Maps SDK key (map screen) |
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 househunter.jks` |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Site support

Sites are **config-driven**: one JSON object per site (URL template + CSS
selectors) in `app/src/main/assets/sites_default.json`. Sites that block bots
(NoBroker, housing.com, OLX, Facebook) ship disabled — open the site in a
browser, copy its search URL, and enter the selectors with **Add site**.

> Personal use only. Scraping is bound by each site's Terms of Service; the
> scraper rate-limits itself and respects robots.txt. Facebook Groups cannot be
> scraped (login required) — use manual copy/paste.

See [plan.md](plan.md) for the full implementation plan.