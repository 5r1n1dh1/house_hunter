#!/usr/bin/env python3
"""
Offline validation for the scraper rules.

Runs the SAME parsing logic the Kotlin app uses (embedded-JSON extraction, JSON-LD
price, rent plausibility) against the live sites, so we can prove a source returns
real listings before shipping a config for it.

    python3 tools/validate_sources.py sahakara-nagar
    python3 tools/validate_sources.py --city pune --locality baner

This is a developer tool; it is NOT part of the Android app.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import subprocess

UA = "HouseHunterBot/1.0 (personal rental aggregator; +https://github.com/5r1n1dh1/house_hunter)"
# Property-site CDNs (99acres, OLX) answer 403 to anything that does not look like a
# real Chrome navigation, so the validator mirrors the app's browser-shaped headers.
BROWSER_UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/122.0.0.0 Safari/537.36")
BROWSER_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "sec-ch-ua": '"Chromium";v="122", "Not(A:Brand";v="24"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Linux"',
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Upgrade-Insecure-Requests": "1",
}
MIN_RENT, MAX_RENT = 500.0, 10_000_000.0


def fetch(url: str, referer: str | None = None) -> str | None:
    """Fetch a page the way the app does.

    Uses curl rather than urllib on purpose: these CDNs fingerprint the TLS
    handshake, and curl is the client that actually gets through (urllib gets 403
    even with identical headers).
    """
    cmd = [
        "curl", "-sL", "--compressed", "--max-time", "45",
        "-A", BROWSER_UA,
        "-H", f"Accept: {BROWSER_HEADERS['Accept']}",
        "-H", f"Accept-Language: {BROWSER_HEADERS['Accept-Language']}",
        "-H", f"sec-ch-ua: {BROWSER_HEADERS['sec-ch-ua']}",
        "-H", f"sec-ch-ua-mobile: {BROWSER_HEADERS['sec-ch-ua-mobile']}",
        "-H", f"sec-ch-ua-platform: {BROWSER_HEADERS['sec-ch-ua-platform']}",
        "-H", f"Sec-Fetch-Dest: {BROWSER_HEADERS['Sec-Fetch-Dest']}",
        "-H", f"Sec-Fetch-Mode: {BROWSER_HEADERS['Sec-Fetch-Mode']}",
        "-H", f"Sec-Fetch-Site: {BROWSER_HEADERS['Sec-Fetch-Site']}",
        "-H", f"Upgrade-Insecure-Requests: {BROWSER_HEADERS['Upgrade-Insecure-Requests']}",
    ]
    if referer:
        cmd += ["-H", f"Referer: {referer}"]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=60).stdout
        return out.decode("utf-8", "ignore") or None
    except Exception as exc:  # noqa: BLE001 - diagnostics only
        print(f"    ! fetch failed: {exc}")
        return None


# ---------------------------------------------------------------- parsing rules

def extract_array(html: str, anchor: str) -> list[dict]:
    """String-aware scan for the JSON array whose objects contain `anchor`."""
    idx = html.find(f'"{anchor}"')
    if idx == -1:
        return []
    start = html.rfind("[", 0, idx)
    depth, in_str, esc, i = 0, False, False, start
    while i < len(html):
        ch = html[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch in "[{":
                depth += 1
            elif ch in "]}":
                depth -= 1
                if depth == 0:
                    try:
                        data = json.loads(html[start:i + 1])
                    except json.JSONDecodeError:
                        return []
                    return data if isinstance(data, list) else []
        i += 1
    return []


def json_ld_prices(html: str) -> list[dict]:
    out = []
    for m in re.finditer(
        r'\{"@context":"https://schema\.org".*?"priceSpecification":'
        r'\{"@type":"PriceSpecification","price":([0-9.]+)',
        html, re.S,
    ):
        start, end = m.start(), m.end()
        name = re.search(r'"name":"([^"]{5,120})"', html[start:end])
        out.append({
            "price": float(m.group(1)),
            "title": name.group(1) if name else "?",
        })
    return out


def parse_rent(text: str | None):
    if not text or not text.strip():
        return None
    clean = text.replace(",", "").replace("₹", "").replace("Rs", "")
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*lakh", clean, re.I)
    if m:
        return _plausible(float(m.group(1)) * 100_000)
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*k\b", clean, re.I)
    if m:
        return _plausible(float(m.group(1)) * 1_000)
    m = re.search(r"(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)", text, re.I)
    if m:
        v = _plausible(float(m.group(1).replace(",", "")))
        if v is not None:
            return v
    m = re.search(r"([0-9]+(?:,[0-9]+)*(?:\.[0-9]+)?)", text)
    return _plausible(float(m.group(1).replace(",", ""))) if m else None


def _plausible(v):
    return v if v is not None and MIN_RENT <= v <= MAX_RENT else None


# ------------------------------------------------------------------- the sources

def check_nobroker(locality_slug: str, city: str) -> None:
    print("\n=== NoBroker (embedded JSON) ===")
    url = f"https://www.nobroker.in/properties-for-rent-in-{locality_slug}-{city}-page-1"
    html = fetch(url, referer="https://www.nobroker.in")
    if not html:
        print("  FAIL: no response")
        return
    items = extract_array(html, "rent")
    print(f"  listing objects: {len(items)}")
    kept = []
    for it in items:
        rent = _plausible(it.get("rent"))
        if rent is None or not it.get("propertyTitle"):
            continue
        kept.append({
            "rent": rent,
            "title": it["propertyTitle"][:70],
            "bhk": (re.search(r"\d+", str(it.get("type"))) or [None]) and
                   re.search(r"\d+", str(it.get("type"))).group(0),
            "area": it.get("propertySize"),
            "locality": it.get("localityTruncated"),
            "image": str(it.get("originalImageUrl", ""))[:2] or "-",
            "detail": bool(it.get("detailUrl")),
        })
    _report(kept, "locality", locality_slug)


def check_magicbricks(locality_slug: str, city: str) -> None:
    print("\n=== MagicBricks (schema.org JSON-LD) ===")
    url = f"https://www.magicbricks.com/property-for-rent-in-{locality_slug}-{city}-pppfr"
    html = fetch(url, referer="https://www.magicbricks.com")
    if not html:
        print("  FAIL: no response")
        return
    rows = json_ld_prices(html)
    print(f"  cards with JSON-LD price: {len(rows)}")
    kept = [
        {"rent": _plausible(r["price"]), "title": r["title"][:70],
         "bhk": (re.search(r"(\d+)\s*BH?K", r["title"], re.I) or [None, None])[1] if
                re.search(r"(\d+)\s*BH?K", r["title"], re.I) else None,
         "area": None, "locality": None, "image": "-", "detail": False}
        for r in rows
    ]
    _report(kept, "locality", locality_slug)


def check_stanza(city: str, locality: str | None) -> None:
    print("\n=== Stanza Living (sitemap + JSON-LD) ===")
    index = fetch("https://www.stanzaliving.com/sitemap.xml")
    if not index:
        print("  FAIL: no sitemap")
        return
    maps = re.findall(r"<loc>([^<]+)</loc>", index)
    residence_map = next((m for m in maps if "residence" in m), None)
    if not residence_map:
        print("  FAIL: no residence sitemap in index")
        return
    xml = fetch(residence_map)
    urls = re.findall(r"<loc>([^<]+)</loc>", xml or "")
    city_slug = city.lower()
    hit = [u for u in urls if f"/{city_slug}/" in u and (not locality or locality.lower() in u.lower())]
    print(f"  residence urls: {len(urls)} | matching {city_slug}: {len(hit)}")
    if not hit:
        nearby = [u for u in urls if f"/{city_slug}/" in u][:3]
        print(f"  no {locality} residences; sample of {city_slug}: {len(nearby)}")
        hit = nearby
    for prop_url in hit[:3]:
        page = fetch(prop_url)
        price = re.search(r'"price":([0-9.]+)', page or "")
        if not price:
            continue
        rent = _plausible(float(price.group(1)))
        print(f"    {'OK ' if rent else 'BAD'} ₹{price.group(1):>8}  {prop_url.rsplit('/', 1)[-1]}")
    _report([{"rent": _plausible(float(p))} for p in
             re.findall(r'"price":([0-9.]+)', " ".join(filter(None, [fetch(u) for u in hit[:3]])))],
            "city", city_slug)


def check_99acres(locality_slug: str, city: str) -> None:
    print("\n=== 99acres (browser headers + card selectors) ===")
    html = fetch(f"https://www.99acres.com/property-for-rent-in-{city}-ffid",
                 referer="https://www.99acres.com/")
    if not html:
        print("  FAIL: no response (WAF blocked)")
        return
    cards = html.split('tupleNew__tupleWrapTopaz')[1:] or html.split('tupleNew__tupleWrap')[1:]
    rows = []
    for card in cards:
        price = re.search(r'class="[^"]*tupleNew__priceValWrap[^"]*"[^>]*>\s*<span>([^<]+)</span>', card)
        if not price:
            continue
        title = re.search(r'class="[^"]*tupleNew__propType[^"]*"[^>]*>\s*<span[^>]*>([^<]+)</span>', card)
        rows.append({
            "rent": parse_rent(price.group(1)),
            "title": title.group(1).strip() if title else "",
            "area": (re.search(r'class="[^"]*tupleNew__areaType[^"]*"[^>]*>\s*([0-9,.]+)\s*sq', card)
                     or [None, None])[1],
            "link": bool(re.search(r'href="(https://www\.99acres\.com/[^"]*spid-[A-Za-z]?\d+)"', card)),
            "owner": (re.search(r'class="[^"]*tupleNew__pbL1[^"]*"[^>]*>\s*([^<]{2,40})', card)
                      or [None, None])[1],
        })
    print(f"  cards: {len(cards)} | priced: {len(rows)}")
    _report(rows, "locality", locality_slug)
    print(f"  with owner field: {sum(1 for r in rows if r['owner'])}/{len(rows)}")


def _report(rows, key, needle) -> None:
    good = [r for r in rows if r["rent"]]
    bad = [r for r in rows if not r["rent"]]
    print(f"  plausible rents: {len(good)}/{len(rows)}  (rejected {len(bad)})")
    if not good:
        return
    rents = sorted(r["rent"] for r in good)
    print(f"  rent range: ₹{rents[0]:,.0f} - ₹{rents[-1]:,.0f}")
    for r in good[:3]:
        print(f"    ₹{r['rent']:>9,.0f}  {r.get('bhk') or '?'}BHK  "
              f"{(r.get('area') or '?'):>6}  {str(r.get('title'))[:48]}")
    no_img = sum(1 for r in good if r.get("image") in ("-", None))
    print(f"  listings with an image: {len(good) - no_img}/{len(good)}")
    detail = sum(1 for r in good if r.get("detail"))
    print(f"  listings with a detail URL: {detail}/{len(good)}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("locality", nargs="?", default="sahakara-nagar")
    ap.add_argument("--city", default="bangalore")
    ap.add_argument("--stanza-city", default="bengaluru",
                    help="Stanza uses different city slugs (bengaluru, not bangalore)")
    ap.add_argument("--only", choices=["99acres", "nobroker", "magicbricks", "stanza"])
    args = ap.parse_args()

    if args.only in (None, "99acres"):
        check_99acres(args.locality, args.city)
    if args.only in (None, "nobroker"):
        check_nobroker(args.locality, args.city)
    if args.only in (None, "magicbricks"):
        check_magicbricks(args.locality, args.city)
    if args.only in (None, "stanza"):
        check_stanza(args.stanza_city, args.locality)
    return 0


if __name__ == "__main__":
    sys.exit(main())