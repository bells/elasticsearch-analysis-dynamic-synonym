dynamic synonym for ElasticSearch
==================================

dynamic synonym plugin adds a synonym token filter that reloads the synonym file(local file or remote file) at given intervals (default 60s).

Version
-------------

dynamic synonym version | ES version
-----------|-----------
master | 1.6.0 -> master

Installation
--------------

Using the plugin command (inside your elasticsearch/bin directory) the plugin can be installed by:
```
bin/plugin -install analysis-dynamic-synonym  -url https://github.com/bells/elasticsearch-analysis-dynamic-synonym/releases/download/xxx.zip
```

Configuration
-------------

#### `config/dynamic_synonym/synonym.cfg.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
	<comment>dynamic synonym 配置</comment>
	
	<!--用户可以在这里配置本地或者远程同义词文件 required-->
	<entry key="synonyms_path">http://remote_host:8080/synonym.txt</entry>
	<!-- <entry key="synonyms_path">dynamic_synonym/synonym.txt</entry> -->

	<!-- 检测同义词是否修改的时间间隔(单位：秒) not required, default 60s-->
	<!-- <entry key="interval">60</entry> -->
	
	<!-- 同义词是否区分大小写 not required, default false-->
	<!-- <entry key="ignore_case">true</entry> -->
	
	<!-- 使用Apache Solr格式同义词时，true表示将所有同义词扩大到所有等价形式 not required, default false -->
	<!-- <entry key="expand">false</entry> -->
	
	<!-- 如果设置，则表明为wordnext同义词 not required -->
	<!-- <entry key="format">wordnet</entry> -->
</properties>
```

Example
--------------

```json
{
	"index" : {
	    "analysis" : {
	        "analyzer" : {
	            "synonym" : {
	                "tokenizer" : "whitespace",
	                "filter" : ["synonym"]
 	           }
	        },
	        "filter" : {
	            "synonym" : {
	                "type" : "dynamic_synonym",
	                "config_path" : "dynamic_synonym/synonym.cfg.xml"
	            }
	        }
	    }
	}
}
```
说明：
`config_path` is not required. the default value is `dynamic_synonym/synonym.cfg.xml`


热更新同义词说明
----------------

1. 对于本地文件：主要通过文件的修改时间戳(Modify time)来判断是否要重新加载。
2. 对于远程文件：`synonyms_path` 是指一个url。 这个http请求需要返回两个头部，一个是 `Last-Modified`，一个是 `ETag`，只要有一个发生变化，该插件就会去获取新的同义词来更新相应的同义词。

注意： 不管是本地文件，还是远程文件，编码都要求是UTF-8的文本文件
