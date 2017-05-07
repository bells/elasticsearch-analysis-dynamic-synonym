dynamic synonym for ElasticSearch
==================================

The dynamic synonym plugin adds a synonym token filter that reloads the synonym file(local file or remote file) at given intervals (default 60s).

Version
-------------

dynamic synonym version | ES version
-----------|-----------
master| 5.x -> master
5.2.0 |	5.2.0
5.1.1 |	5.1.1
2.3.0 | 2.3.0
2.2.0 | 2.2.0
2.1.0 | 2.1.0
2.0.0 | 2.0.0 
1.6.0 | 1.6.X

Installation
--------------

1. `mvn package`

2. copy and unzip `target/releases/elasticsearch-analysis-dynamic-synonym-{version}.zip` to `your-es-root/plugins/dynamic-synonym`

Example
--------------

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
	        }
	    }
	}
}
```
说明：
`synonyms_path` 是必须要配置的，根据它的值是否是以`http://`开头来判断是本地文件，还是远程文件。 

`interval` 非必须配置的，默认值是60，单位秒，表示间隔多少秒去检查同义词文件是否有更新。

`ignore_case` 非必须配置的， 默认值是false。

`expand` 非必须配置的， 默认值是true。

`format` 非必须配置的， 默认值是空字符串, 如果为wordnet，则表示WordNet结构的同义词。


热更新同义词说明
----------------

1. 对于本地文件：主要通过文件的修改时间戳(Modify time)来判断是否要重新加载。
2. 对于远程文件：`synonyms_path` 是指一个url。 这个http请求需要返回两个头部，一个是 `Last-Modified`，一个是 `ETag`，只要有一个发生变化，该插件就会去获取新的同义词来更新相应的同义词。

注意： 不管是本地文件，还是远程文件，编码都要求是UTF-8的文本文件
