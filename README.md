dynamic synonym for ElasticSearch
==================================

dynamic synonym plugin adds a synonym token filter that reloads the synonym file(local file or remote file) at given intervals (default 60s).

Version
-------------
 1.0.0                       | 1.6.0 -> master  
 

## Installation

Using the plugin command (inside your elasticsearch/bin directory) the plugin can be installed by:
```
bin/plugin -install dynamic-synonym  -url https://github.com/bells/elasticsearch-dynamic-synonym/releases/download/xxx.zip
