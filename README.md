# nightshelf

Overnight indexer that makes a local video clip library searchable by content.
It describes frames with an open-weight vision model, stores the result in a
local SQLite database, and lets you search the library from the command line.

Work in progress: see the open issues for the roadmap.

## Requirements

- Java 21
- Maven 3.9+
- ffmpeg and ffprobe on the PATH (frame extraction)

## Build

```
mvn package
```

## Configuration

All settings come from environment variables.

| Variable      | Default                  | Purpose                                      |
|---------------|--------------------------|----------------------------------------------|
| `CLIPBANK_DIR`| none                     | Clip library directory (read-only)           |
| `DB_PATH`     | `~/.nightshelf/index.db` | SQLite index; must be on a local disk        |
| `OLLAMA_URL`  | none                     | Base URL of the Ollama API (http or https)   |
| `VISION_MODEL`| `gemma3:4b`              | Vision model used to describe frames         |
| `EMBED_MODEL` | `nomic-embed-text`       | Embedding model used for vector search       |
| `FRAME_COUNT` | `3`                      | Frames extracted per clip (1 to 12)          |

Print the effective configuration:

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar
```

Describe one clip with the vision model (needs `CLIPBANK_DIR`, `OLLAMA_URL`,
ffmpeg and a running Ollama):

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar describe /path/to/clip.mp4
```

Scan the library and update the index (no model is called). Added clips become
pending, changed clips go back to pending, clips that vanished are flagged as
removed (never deleted), and a removed clip that comes back is un-flagged:

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar sync
```

The index refuses to open on a network filesystem (SMB, NFS, AFP and similar):
SQLite locking and WAL are not reliable there. It exits with status 2; point
`DB_PATH` at a local disk.

## Overnight run

`run` does everything in one resumable pass: scan the library, update the
index, describe every pending or failed clip with the vision model, then embed
the described clips that have no current vector.

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar run [--max-clips N] [--time-budget MINUTES] \
    [--skip-embed] [--retry-failed] [--allow-mass-removal]
```

- One progress line per clip: key, seconds, `ok` or `failed: reason`.
- `--max-clips N` stops after N clips (handy for a dry run). `--time-budget`
  stops cleanly between clips once the time is used up; embedding the rest
  then waits for the next run (or for `embed`).
- SIGINT and SIGTERM (Ctrl-C, `docker stop`) finish the clip in progress,
  record it, print the summary and exit 0. A hard kill loses at most the clip
  in progress, because every clip is committed on its own; the next run picks
  up the rest.
- A clip that fails is recorded and the run goes on. After 10 failures in a
  row the run stops with exit status 3 (Ollama is probably down). Failed clips
  are retried at the next run, but a clip that failed in 3 separate runs is
  left alone until you pass `--retry-failed`.
- If the scan finds no clips, or more than half of the indexed clips are
  missing from it, the run refuses with exit status 2: an unmounted library
  must not look like a deletion. `--allow-mass-removal` overrides this. Clips
  that really vanished are only flagged as removed, never deleted.
- Temporary frames are deleted after every clip, also on failure and on stop.
  A clip is read by ffmpeg once per run.

Each run is stored in the index (start, end, counts of described, failed and
left-alone clips, embedded count, total and average seconds, models used).
`stats` prints the last run and the totals of the index:

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar stats
```

Time: in an earlier measurement on a Mac, describing took about 12 seconds
per clip including model start-up, which is a few minutes for 20 clips. That is
one machine and one model; the real figures for your setup are the "per
described clip" numbers that `run` and `stats` print.

## Docker

The image (Java 21 JRE and ffmpeg) runs as an unprivileged user and publishes
no port. The compose file starts the indexer only:

```
cp .env.example .env        # then edit it; .env is git-ignored
docker compose build
docker compose run --rm indexer run --max-clips 5
docker compose run --rm indexer stats
```

- The clip library is mounted read-only at `/clipbank`; the container user
  (uid 10001) must be able to read it.
- The index lives on the local named volume `index`. Do not point it at a
  network mount; the index refuses to open on one.
- The root filesystem is read-only, with a tmpfs for `/tmp` (frames and the
  SQLite library), all capabilities dropped and `no-new-privileges` set.
- `OLLAMA_URL` comes from the environment (`.env`). `.env.example` has
  placeholders only.
- `docker compose stop` sends SIGTERM and waits up to two minutes, so the
  clip in progress is finished and recorded.

Optionally, an Ollama container with an NVIDIA GPU is available as a separate
profile: `docker compose --profile ollama up -d ollama`, with
`OLLAMA_URL=http://ollama:11434`. Its port is published on `127.0.0.1` of the
host only. **Ollama has no authentication**: it must only be reachable from the
machine that runs the indexer, so never publish its port on all interfaces or
expose it to a shared network. Traffic to it is plain http (see below).

## Search

Described clips can be searched by content. First compute their embeddings
(needs `OLLAMA_URL` and the `EMBED_MODEL`); only described clips that have no
vector for the current `EMBED_MODEL` are processed, so changing the model
recomputes everything:

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar embed
```

Then search:

```
java -jar target/nightshelf-0.1.0-SNAPSHOT.jar search "rainy city at night" \
    --channel mychannel --status unused --limit 10
```

Results combine SQLite full-text search (FTS5) and vector similarity with
reciprocal rank fusion. If Ollama is not configured or not reachable, the
search falls back to full-text only and prints one `note:` line. Each result
shows the key, score, the cosine similarity of the query to the clip (`sim 0.62`, or `sim -` when the clip was found by full-text search only), duration, a one-line description, the status per channel
and the channels it was used on (from `catalog.json`, which is only read), and
the file path.

| Option                 | Meaning                                                              |
|------------------------|----------------------------------------------------------------------|
| `--channel NAME`       | Look at the status and uses of this channel                           |
| `--status S`           | With `--channel`: `approved`, `rejected`, `unused` (never used on it) or `any`. Default: hide clips rejected for the channel |
| `--orientation O`      | Only clips with this orientation in the catalog                       |
| `--min-duration SEC`   | Only clips at least this long in the catalog                          |
| `--min-similarity X`   | Keep only results whose cosine similarity is at least X (0 to 1). Off by default. Results found by full-text search only have no similarity and are dropped when this is set |
| `--limit N`            | Number of results, 1 to 50 (default 10)                               |

The search text is never passed to SQLite as query syntax: it is split into
words and each word is quoted.

## Security note

Traffic to Ollama is plain `http` (unencrypted). That includes the frames sent
to describe a clip, the clip descriptions sent by `embed`, and every search
query sent by `search`: all of it is readable by anyone on the path to the
Ollama host. Run Ollama on `localhost`, on a trusted LAN, or reach it through
an SSH tunnel (for example `ssh -L 11434:localhost:11434 host`).
