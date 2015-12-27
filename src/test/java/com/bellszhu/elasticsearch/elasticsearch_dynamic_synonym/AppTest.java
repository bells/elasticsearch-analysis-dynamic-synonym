package com.bellszhu.elasticsearch.elasticsearch_dynamic_synonym;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/**
 * Unit test for simple App.
 */
public class AppTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public AppTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( AppTest.class );
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp () throws IOException
    {
    	
    	InputStream input = null;
		try {
			input = new FileInputStream("config/dynamic_synonym/synonym.cfg.xml");
		} catch (FileNotFoundException e) {
			//logger.error("dynamic-synonym", e);
			System.out.println("****");
		}
    	Properties props = new Properties();
    	
    	props.loadFromXML(input);
    	
    	List<String> remoteExtDictFiles = new ArrayList<String>();
    	String remoteExtDictCfg = props.getProperty("remote_synonym");
		if (remoteExtDictCfg != null) {

			String[] filePaths = remoteExtDictCfg.split(";");
			if (filePaths != null) {
				for (String filePath : filePaths) {
					if (filePath != null && !"".equals(filePath.trim())) {
						remoteExtDictFiles.add(filePath);

					}
				}
			}
		}
		
		System.out.println(remoteExtDictFiles);
    	
    	String location = "http://localhost:8080/syn.txt";
        RemoteFileMonitor m = new RemoteFileMonitor(location);
        Reader in = m.getRemoteWords(location);
        LineNumberReader br = new LineNumberReader(in);
        String line = null;
        while ((line = br.readLine()) != null) {
        	System.out.println(line);
            if (line.length() == 0 || line.charAt(0) == '#') {
            	System.out.println("skip");
              continue; // ignore empty lines and comments
            }
        }
        System.out.println("over");
    }
    
    public class RemoteFileMonitor implements Runnable {

		private CloseableHttpClient httpclient = HttpClients.createDefault();

		/** 上次更改时间 */
		private String last_modified;

		/** 资源属性 */
		private String eTags;

		/** 请求地址 */
		private String location;

		public RemoteFileMonitor(String location) {
			this.location = location;
			this.last_modified = null;
			this.eTags = null;
		}

		/**
		 * 监控流程： ①向词库服务器发送Head请求 ②从响应中获取Last-Modify、ETags字段值，判断是否变化
		 * ③如果未变化，休眠1min，返回第①步 ④如果有变化，重新加载词典 ⑤休眠1min，返回第①步
		 */

		public void run() {

			// 超时设置
			RequestConfig rc = RequestConfig.custom()
					.setConnectionRequestTimeout(10 * 1000)
					.setConnectTimeout(10 * 1000).setSocketTimeout(15 * 1000)
					.build();

			HttpHead head = new HttpHead(location);
			head.setConfig(rc);

			// 设置请求头
			if (last_modified != null) {
				head.setHeader("If-Modified-Since", last_modified);
			}
			if (eTags != null) {
				head.setHeader("If-None-Match", eTags);
			}

			CloseableHttpResponse response = null;
			try {

				response = httpclient.execute(head);

				// 返回200 才做操作
				if (response.getStatusLine().getStatusCode() == 200) {

					
				} else if (response.getStatusLine().getStatusCode() == 304) {
					// 没有修改，不做操作
					// noop
				} else {
					
				}

			} catch (Exception e) {
				
			} finally {
				try {
					if (response != null) {
						response.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}


		/**
		 * 从远程服务器上下载自定义词条
		 */
		private Reader getRemoteWords(String location) {

			RequestConfig rc = RequestConfig.custom()
					.setConnectionRequestTimeout(10 * 1000)
					.setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
					.build();
			CloseableHttpClient httpclient = HttpClients.createDefault();
			CloseableHttpResponse response;
			BufferedReader in = null;
			HttpGet get = new HttpGet(location);
			get.setConfig(rc);
			try {
				response = httpclient.execute(get);
				if (response.getStatusLine().getStatusCode() == 200) {

					String charset = "UTF-8";
					// 获取编码，默认为utf-8
					if (response.getEntity().getContentType().getValue()
							.contains("charset=")) {
						String contentType = response.getEntity()
								.getContentType().getValue();
						charset = contentType.substring(contentType
								.lastIndexOf("=") + 1);
					}
					in = new BufferedReader(new InputStreamReader(response
							.getEntity().getContent(), charset));
					/*String line;
					while ((line = in.readLine()) != null) {
						buffer.add(line);
					}
					in.close();*/
					response.close();
					return in;
				}
				response.close();
			} catch (ClientProtocolException e) {
				//logger.error("getRemoteWords {} error", e, location);
			} catch (IllegalStateException e) {
				//logger.error("getRemoteWords {} error", e, location);
			} catch (IOException e) {
				//logger.error("getRemoteWords {} error", e, location);
			}
			return in;
		}
	}
}
