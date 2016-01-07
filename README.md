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

Example:

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
	                "config_path" : "dynamic_synonym/synonym.cfg.xml"  # not required, default: dynamic_synonym/synonym.cfg.xml
	            }
	        }
	    }
	}
}
```

### 热更新同义词说明

目前该插件支持热更新 IK 分词，通过上文在 IK 配置文件中提到的如下配置

```xml
 	<!--用户可以在这里配置远程扩展字典 -->
	<entry key="remote_ext_dict">location</entry>
 	<!--用户可以在这里配置远程扩展停止词字典-->
	<entry key="remote_ext_stopwords">location</entry>
```

其中 `location` 是指一个 url，比如 `http://yoursite.com/getCustomDict`，该请求只需满足以下两点即可完成分词热更新。

1. 该 http 请求需要返回两个头部(header)，一个是 `Last-Modified`，一个是 `ETag`，这两者都是字符串类型，只要有一个发生变化，该插件就会去抓取新的分词进而更新词库。

2. 该 http 请求返回的内容格式是一行一个分词，换行符用 `\n` 即可。

满足上面两点要求就可以实现热更新分词了，不需要重启 ES 实例。

可以将需自动更新的热词放在一个 UTF-8 编码的 .txt 文件里，放在 nginx 或其他简易 http server 下，当 .txt 文件修改时，http server 会在客户端请求该文件时自动返回相应的 Last-Modified 和 ETag。可以另外做一个工具来从业务系统提取相关词汇，并更新这个 .txt 文件。