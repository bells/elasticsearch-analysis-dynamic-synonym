# Dynamic Synonym for ElasticSearch

The dynamic synonym plugin adds a synonym token filter that reloads the synonym file (local file or remote file) at given intervals (default 60s).

## Do you need this plugin?

Elasticsearch already supports native search-time synonym reloading:
[reload search analyzers](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-reload-search-analyzers)
is available since 7.3, and [Synonyms Management APIs](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-synonyms-put-synonym)
since 8.10 automatically reload search analyzers when a synonym set is updated.
These capabilities continue in 9.x. Prefer the native APIs for new deployments.

This plugin remains useful for **direct HTTP(S) dictionary polling** and **automatic
local-file polling**, especially for existing external dictionary services. The
current build targets **8.7.1 only**, not other 8.x versions or 9.x. See the
[version assessment and migration guide](docs/ELASTICSEARCH_SUPPORT.md) (中文).

## Version

The current POM targets **Elasticsearch 8.7.1** and **Java 17**. Build and install
against the exact Elasticsearch version declared by the plugin descriptor;
the historical branch names below are not a guarantee of compatibility with every 8.x release.

| dynamic synonym version | ES version    |
|-------------------------|---------------|
| master                  | 8.7.1         |
| 7.4.2                   | 7.4.2         |
| 6.1.4                   | 6.1.4         |
| 5.2.0                   | 5.2.0         |
| 5.1.1                   | 5.1.1         |
| 2.3.0                   | 2.3.0         |
| 2.2.0                   | 2.2.0         |
| 2.1.0                   | 2.1.0         |
| 2.0.0                   | 2.0.0         |
| 1.6.0                   | 1.6.X         |

## Installation

1. Use JDK 17 and Maven (3.9.x recommended), then run `mvn clean verify`.

2. copy and unzip `target/releases/elasticsearch-analysis-dynamic-synonym-{version}.zip` to `your-es-root/plugins/dynamic-synonym`

## Example

```json
{
    "index" : {
        "analysis" : {
            "analyzer" : {
                "synonym" : {
                    "tokenizer" : "whitespace",
                    "filter" : ["remote_synonym"]
                }
            },
            "filter" : {
                "remote_synonym" : {
                    "type" : "dynamic_synonym",
                    "synonyms_path" : "http://host:port/synonym.txt",
                    "interval": 30
                },
                "local_synonym" : {
                    "type" : "dynamic_synonym",
                    "synonyms_path" : "synonym.txt"
                },
                "synonym_graph" : {
                    "type" : "dynamic_synonym_graph",
                    "synonyms_path" : "http://host:port/synonym.txt"
                }
            }
        }
    }
}
```
### Configuration

`type`: `dynamic_synonym` or `dynamic_synonym_graph`, *mandatory*

`synonyms_path`: A file path relative to the Elasticsearch config directory, an absolute path permitted by the host, or an HTTP(S) URL, *mandatory*

`interval`: Positive delay in seconds between completed polling rounds, default: `60`, *optional*

`ignore_case`: Ignore case when matching input tokens, default: `false`, *optional*. Rule normalization also depends on the preceding analysis chain.

`expand`: Expand, default: `true`, *optional*

`lenient`: Lenient on exception thrown when importing a synonym, default: `false`, *optional*

`format`: Synonym file format, default: `''`, *optional*. For WordNet structure this can be set to `'wordnet'`

`updateable`: Restrict the filter to search-time analysis when `true`, default: `false`, *optional*. This does not enable or disable polling; both modes reload on the configured interval.

For multi-token synonyms, prefer `dynamic_synonym_graph` in a search analyzer.
Index-time graph use requires appropriate graph flattening. Reloading synonyms
does not change tokens already stored in an index.

## Update mechanism

* Local files: Check modification time, size and file identity. Relative paths resolve under the Elasticsearch config directory. Publish changes with a new modification time or atomic file replacement; same-size, same-timestamp in-place edits are not guaranteed to be detected.
* Remote files: Initially GET the dictionary, then check with conditional HEAD. Changed or missing validators on HEAD 200 trigger GET; 304 skips reloading. HEAD 405/501 falls back to GET. Other errors retain the previous dictionary.
* A version is committed only after the complete dictionary parses successfully. Failed downloads, missing files and invalid rules preserve the previous map and are retried. Initial load failures reject analyzer creation.
* Empty dictionaries are valid and can transition to or from non-empty dictionaries. A token stream takes the latest snapshot at `reset()`; in-flight analysis keeps its original version.
* Each analysis chain owns its parser and source. Index removal or plugin shutdown releases polling tasks, HTTP clients and parsing analyzers.

Use UTF-8 dictionaries (remote responses may declare another charset). Reloading
is asynchronous across nodes and does not invalidate the Elasticsearch request
cache; clear the relevant cache after updates when required.

These failure semantics intentionally replace the old empty/`1=>1` fallback.
The polling scheduler now uses a delay after each completed round, avoiding
catch-up bursts after slow requests.

## Development documentation

* [Agent collaboration guide](AGENTS.md) — repository rules and change boundaries (中文).
* [Architecture](docs/ARCHITECTURE.md) — components, configuration and reload behavior (中文).
* [Development and verification](docs/DEVELOPMENT.md) — build commands, test coverage and packaging checks (中文).
* [Project review](docs/PROJECT_REVIEW.md) — dated findings and verification evidence (中文).

Run `mvn clean verify` and `python3 scripts/verify-package.py` before delivery.
GitHub Actions runs these checks on pushes, pull requests and manual dispatch,
then installs the verified ZIP into an Elasticsearch 8.7.1 image for a container
smoke test. Successful package jobs provide the ZIP; JUnit reports and a test
summary remain available when tests fail. A
`v8.7.1` tag matching the POM version also publishes the tested ZIP to a GitHub
Release and a versioned Docker image after both checks pass. Docker publishing
requires the repository's `DOCKER_USERNAME` and `DOCKER_PASSWORD` secrets.
Local container checks require a running Docker daemon; see the
[development guide](docs/DEVELOPMENT.md).
