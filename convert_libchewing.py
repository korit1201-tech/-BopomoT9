"""
從 libchewing-data (Codeberg) 下載 tsi.csv + word.csv，
轉換成 app 的 dict_tw.txt 格式：word\tzhuyin\tweight

libchewing 格式：word,frequency,zhuyin (注音音節之間有空格)
目標格式：    word\tzhuyin\tweight        (注音合成一串，無空格)
"""

import urllib.request, ssl, sys, os

OUT_FILE = r"C:\Users\Korit\ai\ime\app\src\main\assets\dict_tw.txt"
BACKUP   = r"C:\Users\Korit\ai\ime\app\src\main\assets\dict_tw_backup.txt"

TSI_URL  = "https://codeberg.org/chewing/libchewing-data/raw/branch/main/dict/chewing/tsi.csv"
WORD_URL = "https://codeberg.org/chewing/libchewing-data/raw/branch/main/dict/chewing/word.csv"

# SSL bypass for corporate proxy
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def fetch(url):
    print(f"Downloading: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, context=ctx, timeout=60) as r:
        data = r.read()
    print(f"  -> {len(data)} bytes")
    return data.decode("utf-8", errors="replace")

def parse_chewing_csv(text):
    """Parse libchewing CSV: word,frequency,zhuyin_with_spaces"""
    entries = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(",", 2)  # max 3 parts
        if len(parts) < 2:
            continue
        word = parts[0].strip()
        try:
            freq = int(parts[1].strip())
        except ValueError:
            continue
        zhuyin_raw = parts[2].strip() if len(parts) > 2 else ""
        # Remove spaces between syllables to get compact zhuyin
        zhuyin = zhuyin_raw.replace(" ", "")
        if word and zhuyin:
            entries.append((word, zhuyin, freq))
    return entries

def main():
    # Backup old dict
    if os.path.exists(OUT_FILE) and not os.path.exists(BACKUP):
        print("Backing up old dict_tw.txt...")
        with open(OUT_FILE, "rb") as f:
            old = f.read()
        with open(BACKUP, "wb") as f:
            f.write(old)
        print(f"  Backup saved to {BACKUP}")

    # Download
    tsi_text  = fetch(TSI_URL)
    word_text = fetch(WORD_URL)

    # Parse
    print("Parsing tsi.csv (phrases)...")
    tsi_entries  = parse_chewing_csv(tsi_text)
    print(f"  -> {len(tsi_entries)} phrase entries")

    print("Parsing word.csv (characters)...")
    word_entries = parse_chewing_csv(word_text)
    print(f"  -> {len(word_entries)} character entries")

    # Merge: word entries first (single chars), then phrases
    # Deduplicate by (word, zhuyin) pair, keep highest frequency
    seen = {}
    for word, zhuyin, freq in word_entries + tsi_entries:
        key = (word, zhuyin)
        if key not in seen or seen[key] < freq:
            seen[key] = freq

    # Sort by frequency descending for readability
    all_entries = sorted(
        [(word, zhuyin, freq) for (word, zhuyin), freq in seen.items()],
        key=lambda x: -x[2]
    )
    print(f"Total unique entries: {len(all_entries)}")

    # Write
    print(f"Writing to {OUT_FILE}...")
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for word, zhuyin, freq in all_entries:
            f.write(f"{word}\t{zhuyin}\t{freq}\n")

    size_mb = os.path.getsize(OUT_FILE) / 1024 / 1024
    print(f"Done! File size: {size_mb:.2f} MB")
    print(f"Sample entries:")
    for word, zhuyin, freq in all_entries[:10]:
        print(f"  {word}\t{zhuyin}\t{freq}")

if __name__ == "__main__":
    main()
