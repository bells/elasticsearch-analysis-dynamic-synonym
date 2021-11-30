# Dynamic Synonym for ElasticSearch

The dynamic synonym plugin adds a synonym token filter that reloads the synonym file (local file or remote file) at given intervals (default 60s).

## Version

dynamic synonym version | ES version
-----------|-----------
master| 7.x -> master
6.1.4 |	6.1.4
5.2.0 |	5.2.0
5.1.1 |	5.1.1
2.3.0 | 2.3.0
2.2.0 | 2.2.0
2.1.0 | 2.1.0
2.0.0 | 2.0.0 
1.6.0 | 1.6.X

## Installation

1. `mvn package`

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

`synonyms_path`: A file path relative to the Elastic config file or an URL, *mandatory*

`interval`: Refresh interval in seconds for the synonym file, default: `60`, *optional*

`ignore_case`: Ignore case in synonyms file, default: `false`, *optional*

`expand`: Expand, default: `true`, *optional* 

`lenient`: Lenient on exception thrown when importing a synonym, default: `false`, *optional* 

`format`: Synonym file format, default: `''`, *optional*. For WordNet structure this can be set to `'wordnet'`


## Update mechanism

* Local files: Determined by modification time of the file, if it has changed the synonyms wil
* Remote files: Reads out the `Last-Modified` and `ETag` http header. If one of these changes, the synonyms will be reloaded. 

**Note:** File encoding should be an utf-8 text file. 
