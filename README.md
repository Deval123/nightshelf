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
