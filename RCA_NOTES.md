# RCA: work distances, favourites, posting date, call button

## 1. Distances to work1 AND work2 not shown

**Symptom:** Cards show no distance at all (previously one bogus value).

**RCA:** Two independent defects stacked.

1. `ListingCard` takes a single `workDistanceKm: Double?` and `HomeScreen` passes
   only `ComputeDistance.distanceTo(listing, state.prefs.work1)` (HomeScreen.kt:297).
   **work2 was never wired to the card.** Only the header showed the work2 label.
2. `distanceTo` returns null when `listing.lat`/`lng` are null. Coordinates are
   filled in by `enrichCoordinates`, which geocodes **distinct localities, capped at
   `MAX_LOCALITY_GEOCODES = 6`**, with a 1.1s per-request Nominatim throttle, running
   on a detached scope. So distances appear late, or never for locality number 7+.
   That is honest but the user sees "nothing".

**Fix:** show both distances (work1 + work2), and compute from the locality fallback
so a card always has *something* to show once enrichment lands.

## 2. Call always says "no phone number on this listing"

**Symptom:** Every card's call button is disabled with that message.

**RCA:** `sites_default.json` declares **no `phone` selector for any of the 13
sites** — verified: the only site with `selectors.postedDate` is MagicBricks; none
has `phone` or `detailFields.phone`. So `phone` is always null
(SiteScraper.kt:379) and `!listing.phone.isNullOrBlank()` is always false.

Live research on what is actually published (curl, browser headers):

| source | phone in listing markup? | date available? |
|---|---|---|
| NoBroker | NO - gated behind login; `phoneStatus`/`phoneExistLoading` are signup state, not per-listing | YES - `creationDate` epoch ms on all 50 listings, plus `lastUpdateDate` |
| Razorent | NO - `contactViaPhone/Chat/WhatsApp/Email` flags only, no number | YES - `createdAt` ISO-8601, `updatedAt` |
| MagicBricks | not in search markup (anti-bot page) | `datePosted` absent in fetched markup |
| 99acres | blocked to plain curl (564 B) - webview engine only | unknown |
| bengaluru.rent | NO - cards are `rent / bhk / photos`; detail `?pin=` also has none | NO |

**Important trap found:** a naive `\b[6-9]\d{9}\b` regex on bengaluru.rent matches
**Google AdSense slot IDs** (`data-ad-slot="9586583695"`, `6401027475`) - not owner
phones. Any loose phone regex would call owners with advertisers.

**Conclusion:** call buttons cannot work from these sources without logged-in
sessions or an owner-response round trip. The honest fix is to stop promising a
call and offer the channel each site actually provides (open the listing, or use the
site's chat), plus extract the phone on the **detail** page where it is published.
Real numbers only - no invented ones.

## 3. Posting date not shown

**RCA:** `Listing.postedAt` exists and is used for sorting, but **no UI renders it**.
`selectors.postedDate` is configured for MagicBricks only, and `postedAt` is never
assigned by any parser.

**Fix:** add a relative-age chip ("2d ago") driven by `postedAt`; parse
NoBroker `creationDate` and Razorent `createdAt`.

## 4. Favourites list

**RCA:** Feature absent entirely (no `avourite` anywhere in main).

**Fix:** persist only listing IDs in DataStore (consistent with the "no listing data
on disk" rule), heart toggle on card + detail, and a Favourites route/tab.

## Plan
1. `workDistanceKm2` param on ListingCard; render both pills; keep the `~` marker.
2. Relative-age chip from `postedAt`; parse NoBroker `creationDate`, Razorent `createdAt`.
3. Replace dead call button with a truthful "contact" action (open listing / site chat).
4. Favourites: DataStore ID set, heart on card + detail, Favourites screen + route.
5. Unit tests for the age formatter and favourites store; CI.