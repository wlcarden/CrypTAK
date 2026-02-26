# Incident Tracker

Automated incident monitoring service for TAK. Polls configurable data sources on a schedule, filters by geographic area and keywords, and injects [Cursor on Target (CoT)](https://www.mitre.org/sites/default/files/pdf/09_4937.pdf) markers into FreeTAKServer for display on ATAK clients and web maps.

## Architecture

```
Data Sources          Analysis Pipeline          TAK Integration
─────────────        ───────────────────        ────────────────
RSS/Atom feeds ──┐
NWS alerts ──────┤    Dedup ──► Keyword     ┌─► CoT XML builder
USGS earthquakes ├──►  Filter   Filter ─────┤       │
DC Open Data ────┤              │           │   FTS TCP client
Waze GeoRSS ─────┤         AI analysis     │       │
Reddit JSON ─────┤        (optional)        │   FreeTAKServer
StopICE XML ─────┘              │           │       │
                        Geocoder ───────────┘   TAK map
```

**Structured sources** (NWS, USGS, Waze, DC Crime, StopICE) provide coordinates directly and bypass the AI pipeline. **Unstructured sources** (RSS, Reddit) require the optional AI analyzer to extract location and category.

## Data Sources

| Source       | Type         | Data                       | Auth Required |
| ------------ | ------------ | -------------------------- | ------------- |
| RSS/Atom     | Unstructured | Any feed URL               | No            |
| NWS          | Structured   | Weather alerts by zone     | No            |
| USGS         | Structured   | Earthquakes by magnitude   | No            |
| DC Open Data | Structured   | Crime incidents (ArcGIS)   | No            |
| Waze         | Structured   | Traffic alerts (GeoRSS)    | No            |
| Reddit       | Unstructured | Subreddit posts (JSON)     | No            |
| StopICE      | Structured   | Crowdsourced reports (XML) | No            |

All sources are disabled by default. Enable and configure in `config.yaml`.

## Quick Start

### Docker (recommended)

```bash
# 1. Configure
cp config.yaml config.local.yaml
#    Edit config.local.yaml — set geo_filter, enable sources, define categories

# 2. (Optional) Set AI key in server/.env for unstructured source analysis
#    ANTHROPIC_API_KEY=sk-ant-...

# 3. Start (requires FreeTAKServer already running)
docker compose --profile incident-tracker up -d
```

### Local Development

```bash
# Prerequisites: Python 3.12+, uv
uv sync --extra dev

# Run tests
uv run pytest tests/ -q

# Run service (FTS must be reachable)
CONFIG_PATH=config.local.yaml uv run python -m src.main
```

## Configuration

All configuration lives in `config.yaml`. Create `config.local.yaml` for local overrides (gitignored).

### Geographic Filter

Incidents outside the defined area are discarded.

```yaml
geo_filter:
  center_lat: 40.7128
  center_lon: -74.0060
  radius_km: 50
  # Or use a bounding box:
  # bbox: [south_lat, west_lon, north_lat, east_lon]
```

### Categories

Categories control how incidents render on the TAK map. Each category maps to a MIL-STD-2525C symbol through two properties:

**`affiliation`** — marker shape and color:

| Value      | Shape      | Color  |
| ---------- | ---------- | ------ |
| `hostile`  | Diamond    | Red    |
| `suspect`  | Diamond    | Yellow |
| `neutral`  | Square     | Green  |
| `unknown`  | Quatrefoil | Yellow |
| `friendly` | Rectangle  | Blue   |

**`icon_type`** — marker icon (FontAwesome):

| Value             | Icon                 | Use Case             |
| ----------------- | -------------------- | -------------------- |
| `raid`            | exclamation-circle   | Critical operations  |
| `law_enforcement` | exclamation-triangle | LE activity          |
| `crime`           | crosshairs           | Violent incidents    |
| `surveillance`    | eye                  | Observed presence    |
| `medical`         | plus-square          | EMS / medical        |
| `fire`            | fire                 | Fire incidents       |
| `natural`         | bolt                 | Weather / geological |
| `traffic`         | car                  | Traffic events       |
| `search`          | search               | Missing persons      |
| `civil`           | bullhorn             | Public gatherings    |
| `general`         | question-circle      | Default              |

**`stale_minutes`** — how long a marker persists before fading.

Example:

```yaml
categories:
  - name: severe_weather
    affiliation: neutral
    icon_type: natural
    stale_minutes: 720

  - name: traffic_hazard
    affiliation: unknown
    icon_type: traffic
    stale_minutes: 60
```

### AI Analysis (Optional)

When enabled, unstructured sources (RSS, Reddit) are processed through Claude to extract location, category, and severity. Structured sources work without AI.

```yaml
ai:
  enabled: true
  model: "claude-haiku-4-5-20251001"
  max_calls_per_hour: 100
```

Requires `ANTHROPIC_API_KEY` in the environment.

### Keyword Filter

Pre-filters unstructured articles before AI analysis to reduce API costs. Case-insensitive substring match. If empty, all articles pass through.

```yaml
keywords:
  - flood
  - earthquake
  - evacuation
  - hazmat
```

## Node-RED WebMap Bridge

The incident tracker sends CoT XML to FreeTAKServer, which broadcasts to connected clients. For browser-based map display, a Node-RED flow bridges FTS to the [worldmap](https://flows.nodered.org/node/node-red-contrib-web-worldmap) node.

An exportable flow is provided at `server/nodered/flows.json`. Import it into your Node-RED instance via **Menu > Import > Select a file**.

The flow handles:

- FTS CoT TCP connection with automatic reconnection
- CoT XML parsing and marker rendering
- Marker caching for persistence across page refreshes

## Testing

```bash
uv run pytest tests/ -q          # 152 tests, <1s
uv run pytest tests/ -v          # Verbose output
uv run pytest tests/test_waze_source.py  # Single module
```

## Project Structure

```
src/
  main.py                  # Scheduler and poll cycle orchestrator
  config.py                # Pydantic v2 configuration models
  models.py                # RawIncident / AnalyzedIncident dataclasses
  dedup.py                 # Fingerprint + Jaccard similarity deduplication
  sources/
    base.py                # Source abstract base class
    rss.py, nws.py, ...    # Source adapters
  analysis/
    keyword_filter.py      # Tier 0 keyword pre-filter
    ai_analyzer.py         # Claude-based extraction (optional)
    geocoder.py            # Nominatim geocoding + geo-fence
  cot/
    builder.py             # CoT XML generation (MIL-STD-2525C)
    fts_client.py          # Async TCP client for FreeTAKServer
tests/                     # Unit tests (one per module)
config.yaml                # Configuration template
Dockerfile                 # Container image
```
