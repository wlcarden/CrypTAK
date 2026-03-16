import mercantile, os, urllib.request, sqlite3, time, sys
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading

# NoVA bounding box
WEST, SOUTH, EAST, NORTH = -77.6, 38.70, -77.0, 39.05
ZOOM_MIN, ZOOM_MAX = 10, 16
OUT = os.path.expanduser('~/Desktop/CrypTAK/nova-streets.sqlite')

# USGS National Map - public domain, bulk-download friendly
# USGSTopo has streets, labels, topo contours - similar to what TAK map shows
TILE_URL = 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}'
HEADERS = {'User-Agent': 'CrypTAK/1.0 (field mission device; contact william.carden@thousand-pikes.com)'}

tiles = list(mercantile.tiles(WEST, SOUTH, EAST, NORTH, range(ZOOM_MIN, ZOOM_MAX+1)))
print(f"Building NoVA offline map: {len(tiles)} tiles z{ZOOM_MIN}-{ZOOM_MAX}", flush=True)

# ATAK MBTiles schema
conn = sqlite3.connect(OUT)
conn.execute('PRAGMA journal_mode=WAL')
conn.execute('CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))')
conn.execute('CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)')
for k,v in [
    ('name','NoVA Streets'),('type','baselayer'),('version','1'),
    ('description','Northern Virginia street map z10-16 (USGS National Map)'),
    ('format','png'),('bounds',f'{WEST},{SOUTH},{EAST},{NORTH}'),
    ('center',f'{(WEST+EAST)/2},{(SOUTH+NORTH)/2},12'),
    ('minzoom',str(ZOOM_MIN)),('maxzoom',str(ZOOM_MAX))
]:
    conn.execute("INSERT OR REPLACE INTO metadata VALUES (?,?)", (k,v))
conn.commit()
conn.close()

lock = threading.Lock()
ok = [0]; fail = [0]

def fetch_tile(t):
    tms_y = (2**t.z - 1) - t.y
    url = TILE_URL.format(z=t.z, y=t.y, x=t.x)
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            data = urllib.request.urlopen(req, timeout=15).read()
            if len(data) > 100:  # valid tile
                return (t.z, t.x, tms_y, data)
            return None
        except Exception:
            time.sleep(0.5 * (attempt+1))
    return None

BATCH = 200
for batch_start in range(0, len(tiles), BATCH):
    batch = tiles[batch_start:batch_start+BATCH]
    results = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(fetch_tile, t): t for t in batch}
        for f in as_completed(futures):
            r = f.result()
            if r:
                results.append(r)
                ok[0] += 1
            else:
                fail[0] += 1

    # Write batch
    c = sqlite3.connect(OUT)
    c.executemany("INSERT OR IGNORE INTO tiles VALUES (?,?,?,?)", results)
    c.commit()
    c.close()

    pct = (batch_start+len(batch))*100//len(tiles)
    size = os.path.getsize(OUT)/1024/1024
    print(f"  {ok[0]}/{len(tiles)} ({pct}%) {size:.1f}MB", flush=True)

size = os.path.getsize(OUT)/1024/1024
print(f"\nDONE: {ok[0]} tiles OK, {fail[0]} failed, {size:.1f}MB -> {OUT}", flush=True)
