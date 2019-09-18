/**
 *
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.HttpParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.ParseException;
import java.util.Locale;

/**
 * @author bellszhu
 */
public class RemoteSynonymFile implements SynonymFile {

    private static final String LAST_MODIFIED_HEADER = "Last-Modified";
    private static final String ETAG_HEADER = "ETag";

    private static Logger logger = LogManager.getLogger("dynamic-synonym");

    private CloseableHttpClient httpclient;

    private String format;

    private boolean expand;

    private Analyzer analyzer;

    private Environment env;

    /**
     * Remote URL address
     */
    private String location;

    private String lastModified;

    private String eTags;

    RemoteSynonymFile(Environment env, Analyzer analyzer,
                      boolean expand, String format, String location) {
        this.analyzer = analyzer;
        this.expand = expand;
        this.format = format;
        this.env = env;
        this.location = location;

        this.httpclient = AccessController.doPrivileged((PrivilegedAction<CloseableHttpClient>) HttpClients::createDefault);

        isNeedReloadSynonymMap();
    }

    static SynonymMap.Builder getSynonymParser(Reader rulesReader, String format, boolean expand, Analyzer analyzer) throws IOException, ParseException {
        SynonymMap.Builder parser;
        if ("wordnet".equalsIgnoreCase(format)) {
            parser = new WordnetSynonymParser(true, expand, analyzer);
            ((WordnetSynonymParser) parser).parse(rulesReader);
        } else {
            parser = new SolrSynonymParser(true, expand, analyzer);
            ((SolrSynonymParser) parser).parse(rulesReader);
        }
        return parser;
    }

    @Override
    public SynonymMap reloadSynonymMap() {
        Reader rulesReader = null;
        try {
            logger.info("start reload remote synonym from {}.", location);
            rulesReader = getReader();
            SynonymMap.Builder parser;

            parser = getSynonymParser(rulesReader, format, expand, analyzer);
            return parser.build();
        } catch (Exception e) {
            logger.error("reload remote synonym {} error!", location, e);
            throw new IllegalArgumentException(
                    "could not reload remote synonyms file to build synonyms",
                    e);
        } finally {
            if (rulesReader != null) {
                try {
                    rulesReader.close();
                } catch (Exception e) {
                    logger.error("failed to close rulesReader", e);
                }
            }
        }
    }

    private CloseableHttpResponse executeHttpRequest(HttpUriRequest httpUriRequest) {
        return AccessController.doPrivileged((PrivilegedAction<CloseableHttpResponse>) () -> {
            try {
                return httpclient.execute(httpUriRequest);
            } catch (Exception e) {
                logger.error("Unable to execute HTTP request.", e);
            }
            // Fix NPE if request remote file failed.
            return new CloseableHttpResponse() {
                private Header[] headers = {new Header() {
                    @Override
                    public String getName() {
                        return "Retry-After";
                    }

                    /**
                     * The delay time
                     * @return time of milliseconds
                     */
                    @Override
                    public String getValue() {
                        return "300000";
                    }

                    @Override
                    public HeaderElement[] getElements() throws org.apache.http.ParseException {
                        return new HeaderElement[0];
                    }
                }, new Header() {
                    @Override
                    public String getName() {
                        return "Content-Type";
                    }

                    @Override
                    public String getValue() {
                        return "text/plain;charset=utf-8";
                    }

                    @Override
                    public HeaderElement[] getElements() throws org.apache.http.ParseException {
                        return new HeaderElement[0];
                    }
                }};

                @Override
                public ProtocolVersion getProtocolVersion() {
                    return new ProtocolVersion("HTTP", 1, 1);
                }

                @Override
                public boolean containsHeader(String s) {
                    return indexOf(s) >= 0;
                }

                private int indexOf(String s) {
                    int i = 0;
                    for (Header header : headers) {
                        if (header.getName().equalsIgnoreCase(s)) {
                            break;
                        }
                        i++;
                    }
                    return i < headers.length ? i : -1;
                }

                @Override
                public Header[] getHeaders(String s) {
                    int index = indexOf(s);
                    if (index >= 0) {
                        return new Header[] { headers[index] };
                    }
                    return null;
                }

                @Override
                public Header getFirstHeader(String s) {
                    return headers[0];
                }

                @Override
                public Header getLastHeader(String s) {
                    return headers[headers.length - 1];
                }

                @Override
                public Header[] getAllHeaders() {
                    return headers;
                }

                @Override
                public void addHeader(Header header) { }

                @Override
                public void addHeader(String s, String s1) { }

                @Override
                public void setHeader(Header header) { }

                @Override
                public void setHeader(String s, String s1) { }

                @Override
                public void setHeaders(Header[] headers) { }

                @Override
                public void removeHeader(Header header) { }

                @Override
                public void removeHeaders(String s) { }

                @Override
                public HeaderIterator headerIterator() {
                    return null;
                }

                @Override
                public HeaderIterator headerIterator(String s) {
                    return null;
                }

                @Override
                public HttpParams getParams() {
                    return null;
                }

                @Override
                public void setParams(HttpParams httpParams) { }

                @Override
                public StatusLine getStatusLine() {
                    return new StatusLine() {

                        @Override
                        public ProtocolVersion getProtocolVersion() {
                            return new ProtocolVersion("HTTP", 1, 1);
                        }

                        @Override
                        public int getStatusCode() {
                            return 503;
                        }

                        @Override
                        public String getReasonPhrase() {
                            return "The server is currently unable to process the request due to temporary server maintenance or overload.";
                        }
                    };
                }

                @Override
                public void setStatusLine(StatusLine statusLine) { }

                @Override
                public void setStatusLine(ProtocolVersion protocolVersion, int i) { }

                @Override
                public void setStatusLine(ProtocolVersion protocolVersion, int i, String s) { }

                @Override
                public void setStatusCode(int i) throws IllegalStateException { }

                @Override
                public void setReasonPhrase(String s) throws IllegalStateException { }

                @Override
                public HttpEntity getEntity() {
                    return null;
                }

                @Override
                public void setEntity(HttpEntity httpEntity) { }

                @Override
                public Locale getLocale() {
                    return null;
                }

                @Override
                public void setLocale(Locale locale) { }

                @Override
                public void close() { }
            };
        });
    }

    /**
     * Download custom terms from a remote server
     */
    public Reader getReader() {
        Reader reader = null;
        RequestConfig rc = RequestConfig.custom()
                .setConnectionRequestTimeout(10 * 1000)
                .setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
                .build();
        CloseableHttpResponse response;
        BufferedReader br = null;
        HttpGet get = new HttpGet(location);
        get.setConfig(rc);
        try {
            response = executeHttpRequest(get);
            if (response.getStatusLine().getStatusCode() == 200) {
                String charset = "UTF-8"; // 获取编码，默认为utf-8
                if (response.getEntity().getContentType().getValue()
                        .contains("charset=")) {
                    String contentType = response.getEntity().getContentType()
                            .getValue();
                    charset = contentType.substring(contentType
                            .lastIndexOf('=') + 1);
                }

                br = new BufferedReader(new InputStreamReader(response
                        .getEntity().getContent(), charset));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    logger.info("reload remote synonym: {}", line);
                    sb.append(line)
                            .append(System.getProperty("line.separator"));
                }
                reader = new StringReader(sb.toString());
            }
        } catch (Exception e) {
            logger.error("get remote synonym reader {} error!", location, e);
            throw new IllegalArgumentException(
                    "Exception while reading remote synonyms file", e);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                logger.error("failed to close bufferedReader", e);
            }
        }
        return reader;
    }

    @Override
    public boolean isNeedReloadSynonymMap() {
        RequestConfig rc = RequestConfig.custom()
                .setConnectionRequestTimeout(10 * 1000)
                .setConnectTimeout(10 * 1000).setSocketTimeout(15 * 1000)
                .build();
        HttpHead head = AccessController.doPrivileged((PrivilegedAction<HttpHead>) () -> new HttpHead(location));
        head.setConfig(rc);

        // 设置请求头
        if (lastModified != null) {
            head.setHeader("If-Modified-Since", lastModified);
        }
        if (eTags != null) {
            head.setHeader("If-None-Match", eTags);
        }

        CloseableHttpResponse response = null;
        try {
            response = executeHttpRequest(head);
            if (response.getStatusLine().getStatusCode() == 200) { // 返回200 才做操作
                if (!response.getLastHeader(LAST_MODIFIED_HEADER).getValue()
                        .equalsIgnoreCase(lastModified)
                        || !response.getLastHeader(ETAG_HEADER).getValue()
                        .equalsIgnoreCase(eTags)) {

                    lastModified = response.getLastHeader(LAST_MODIFIED_HEADER) == null ? null
                            : response.getLastHeader(LAST_MODIFIED_HEADER)
                            .getValue();
                    eTags = response.getLastHeader(ETAG_HEADER) == null ? null
                            : response.getLastHeader(ETAG_HEADER).getValue();
                    return true;
                }
            } else if (response.getStatusLine().getStatusCode() == 304) {
                return false;
            } else {
                logger.info("remote synonym {} return bad code {}", location,
                        response.getStatusLine().getStatusCode());
            }

        } finally {
            try {
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                logger.error("failed to close http response", e);
            }
        }
        return false;
    }
}
