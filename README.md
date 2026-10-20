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

## Security note

Traffic to Ollama is plain `http` (unencrypted), and so are the frames sent in
each request. Run Ollama on `localhost`, on a trusted LAN, or reach it through
an SSH tunnel (for example `ssh -L 11434:localhost:11434 host`).
