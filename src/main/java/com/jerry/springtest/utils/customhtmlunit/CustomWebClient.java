package com.jerry.springtest.utils.customhtmlunit;

import static com.gargoylesoftware.htmlunit.BrowserVersionFeatures.*;
import static com.gargoylesoftware.htmlunit.DefaultPageCreator.*;
import static java.nio.charset.StandardCharsets.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpStatus;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.CustomProxyConfig;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.ProxyAutoConfig;
import com.gargoylesoftware.htmlunit.TopLevelWindow;
import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.WebResponseFromCacheFactory;
import com.gargoylesoftware.htmlunit.WebWindow;

import com.gargoylesoftware.htmlunit.util.NameValuePair;
import com.gargoylesoftware.htmlunit.util.UrlUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomWebClient extends WebClient implements DisposableBean {

	private ThreadLocal<HtmlUnitRedirection> redirectStore = new InheritableThreadLocal<>();

	public static final int maxRedirect = 3;
	private static final int ALLOWED_REDIRECTIONS_SAME_URL = 3;

	public CustomWebClient() {
		super();
	}

	public CustomWebClient(BrowserVersion browserVersion) {
		super(browserVersion);

	}

	public URL findOriginUrl(URL url) {
		URL foundUrl;
		redirectStore.set(new HtmlUnitRedirection(url));
		try {
			Page page = getPage(url);
			foundUrl = page.getUrl();
		} catch (Exception e) {
			e.printStackTrace();
			URL lastestURL = redirectStore.get().getCurrentUrl();
			log.error("exception occured : {} \n lastUrl is : {}", e.getMessage(), lastestURL);
			// exception을 던지지 않고 마지막 호출한 url을 리턴함
			foundUrl = lastestURL;
		} finally {
			redirectStore.remove();
		}
		return foundUrl;
	}

	@Override
	public void download(final WebWindow requestingWindow, final String target,
		final WebRequest request, final boolean checkHash, final boolean forceLoad,
		final boolean forceAttachment, final String description) {
		if (checkRedirectPossible(requestingWindow, target, description)) {
			return;
		}

		doRedirection(request.getUrl());
		super.download(requestingWindow, target, request, checkHash, forceLoad, forceAttachment, description);
	}

	private void doRedirection(URL url) {
		HtmlUnitRedirection htmlUnitRedirection = redirectStore.get();
		htmlUnitRedirection.doRedirection(url);
	}

	/*
	 htmlunit 라이브러리 버전이 바뀌어 아래 조건이 달라지면 함께 수정해주어야 한다.
	 com.gargoylesoftware.htmlunit.WebClient.resolveWindow(...)
	 */
	private boolean checkRedirectPossible(final WebWindow requestingWindow, final String name,
		final String description) {
		if (name != null && !name.isEmpty() && !TARGET_SELF.equals(name)) {
			return false;
		}

		// TopLevelWindow에서 호출하는 js가 아니면 redirection에 영향을 주지 않으니 더이상 download 하지 않는다.
		if (requestingWindow.getClass() != TopLevelWindow.class) {
			return false;
		}

		if (!description.equals("JS set location")) {
			return false;
		}
		return isReachMaxRedirect();
	}

	private boolean isReachMaxRedirect() {
		HtmlUnitRedirection htmlUnitRedirection = redirectStore.get();
		try {
			return htmlUnitRedirection.isReachMaxRedirect();
		} catch (NullPointerException ex) {
			return false;
		}

	}

	@Override
	public WebResponse loadWebResponse(final WebRequest webRequest) throws IOException {
		String requestProtocol = webRequest.getUrl().getProtocol();
		if (List.of(UrlUtils.ABOUT, "file", "data").contains(requestProtocol)) {
			return super.loadWebResponse(webRequest);
		}

		return customLoadWebResponseFromWebConnection(webRequest, ALLOWED_REDIRECTIONS_SAME_URL);
	}

	private WebResponse customLoadWebResponseFromWebConnection(final WebRequest webRequest,
		final int allowedRedirects) throws IOException {
		URL url = webRequest.getUrl();

		final HttpMethod method = webRequest.getHttpMethod();
		final List<NameValuePair> parameters = webRequest.getRequestParameters();

		WebAssert.notNull("url", url);
		WebAssert.notNull("method", method);
		WebAssert.notNull("parameters", parameters);

		url = UrlUtils.encodeUrl(url, getBrowserVersion().hasFeature(URL_MINIMAL_QUERY_ENCODING),
			webRequest.getCharset());
		webRequest.setUrl(url);

		// If the request settings don't specify a custom proxy, use the default client proxy...
		if (webRequest.getProxyHost() == null) {
			final CustomProxyConfig proxyConfig = new CustomProxyConfig(getOptions().getProxyConfig());

			if (proxyConfig.getProxyAutoConfigUrl() != null) {
				if (!UrlUtils.sameFile(new URL(proxyConfig.getProxyAutoConfigUrl()), url)) {
					String content = proxyConfig.getProxyAutoConfigContent();
					if (content == null) {
						content = getPage(proxyConfig.getProxyAutoConfigUrl())
							.getWebResponse().getContentAsString();
						proxyConfig.setProxyAutoConfigContent(content);
					}
					final String allValue = ProxyAutoConfig.evaluate(content, url);

					String value = allValue.split(";")[0].trim();
					if (value.startsWith("PROXY")) {
						value = value.substring(6);
						final int colonIndex = value.indexOf(':');
						webRequest.setSocksProxy(false);
						webRequest.setProxyHost(value.substring(0, colonIndex));
						webRequest.setProxyPort(Integer.parseInt(value.substring(colonIndex + 1)));
					} else if (value.startsWith("SOCKS")) {
						value = value.substring(6);
						final int colonIndex = value.indexOf(':');
						webRequest.setSocksProxy(true);
						webRequest.setProxyHost(value.substring(0, colonIndex));
						webRequest.setProxyPort(Integer.parseInt(value.substring(colonIndex + 1)));
					}
				}
			}
			// ...unless the host needs to bypass the configured client proxy!
			else if (!proxyConfig.shouldBypassProxy(webRequest.getUrl().getHost())) {
				webRequest.setProxyHost(proxyConfig.getProxyHost());
				webRequest.setProxyPort(proxyConfig.getProxyPort());
				webRequest.setProxyScheme(proxyConfig.getProxyScheme());
				webRequest.setSocksProxy(proxyConfig.isSocksProxy());
			}
		}

		// Add the headers that are sent with every request.

		// reflection으로 WebClient의 private method "addDefaultHeaders" 호출
		callAddDefaultHeaders(webRequest);

		// Retrieve the response, either from the cache or from the server.
		final WebResponse fromCache = getCache().getCachedResponse(webRequest);
		final WebResponse webResponse;
		if (fromCache == null) {
			webResponse = getWebConnection().getResponse(webRequest);
		} else {
			webResponse = WebResponseFromCacheFactory.makeWebResponseFromCache(fromCache, webRequest);
		}

		if (isReachMaxRedirect()) {
			return webResponse;
		}

		// Continue according to the HTTP status code.
		final int status = webResponse.getStatusCode();
		if (status == HttpStatus.SC_USE_PROXY) {
			getIncorrectnessListener().notify("Ignoring HTTP status code [305] 'Use Proxy'", this);
		} else if (status >= HttpStatus.SC_MOVED_PERMANENTLY
			&& status <= 308
			&& status != HttpStatus.SC_NOT_MODIFIED
			&& getOptions().isRedirectEnabled()) {

			URL newUrl;
			String locationString = null;
			try {
				locationString = webResponse.getResponseHeaderValue("Location");
				if (locationString == null) {
					return webResponse;
				}
				if (!getBrowserVersion().hasFeature(URL_MINIMAL_QUERY_ENCODING)) {
					locationString = new String(locationString.getBytes(ISO_8859_1), UTF_8);
				}
				newUrl = expandUrl(url, locationString);

				if (getBrowserVersion().hasFeature(HTTP_REDIRECT_WITHOUT_HASH)) {
					newUrl = UrlUtils.getUrlWithNewRef(newUrl, null);
				}
			} catch (final MalformedURLException e) {
				getIncorrectnessListener().notify("Got a redirect status code [" + status + " "
					+ webResponse.getStatusMessage()
					+ "] but the location is not a valid URL [" + locationString
					+ "]. Skipping redirection processing.", this);
				return webResponse;
			}

			if (allowedRedirects == 0) {
				throw new FailingHttpStatusCodeException("Too much redirect for "
					+ webResponse.getWebRequest().getUrl(), webResponse);
			}

			if (status == HttpStatus.SC_MOVED_PERMANENTLY
				|| status == HttpStatus.SC_MOVED_TEMPORARILY
				|| status == HttpStatus.SC_SEE_OTHER) {
				final WebRequest wrs = new WebRequest(newUrl, HttpMethod.GET);
				wrs.setCharset(webRequest.getCharset());

				if (HttpMethod.HEAD == webRequest.getHttpMethod()) {
					wrs.setHttpMethod(HttpMethod.HEAD);
				}
				for (final Map.Entry<String, String> entry : webRequest.getAdditionalHeaders().entrySet()) {
					wrs.setAdditionalHeader(entry.getKey(), entry.getValue());
				}

				if (!isDifferentURL(url, newUrl) && isTopLevelHtml(webResponse)) {
					doRedirection(newUrl);
				}

				return customLoadWebResponseFromWebConnection(wrs, allowedRedirects - 1);
			} else if (status == HttpStatus.SC_TEMPORARY_REDIRECT
				|| status == 308) {

				final WebRequest wrs = new WebRequest(newUrl, webRequest.getHttpMethod());
				wrs.setCharset(webRequest.getCharset());
				if (webRequest.getRequestBody() != null) {
					if (HttpMethod.POST == webRequest.getHttpMethod()
						|| HttpMethod.PUT == webRequest.getHttpMethod()
						|| HttpMethod.PATCH == webRequest.getHttpMethod()) {
						wrs.setRequestBody(webRequest.getRequestBody());
						wrs.setEncodingType(webRequest.getEncodingType());
					}
				} else {
					wrs.setRequestParameters(parameters);
				}

				for (final Map.Entry<String, String> entry : webRequest.getAdditionalHeaders().entrySet()) {
					wrs.setAdditionalHeader(entry.getKey(), entry.getValue());
				}

				if (!isDifferentURL(url, newUrl) && isTopLevelHtml(webResponse)) {
					doRedirection(newUrl);
				}

				return customLoadWebResponseFromWebConnection(wrs, allowedRedirects - 1);
			}
		}

		if (fromCache == null) {
			getCache().cacheIfPossible(webRequest, webResponse, null);
		}
		return webResponse;
	}

	private Boolean isDifferentURL(URL prevUrl, URL newUrl) {
		if (!prevUrl.getHost().equals(newUrl.getHost()))
			return true;
		if (!prevUrl.getPath().equals(newUrl.getPath()))
			return true;
		if (prevUrl.getQuery() == null)
			return newUrl.getQuery() != null;

		return newUrl.getQuery() == null || !newUrl.getQuery().equals(prevUrl.getQuery());
	}

	private boolean isTopLevelHtml(final WebResponse webResponse) throws IOException {
		if (getCurrentWindow().getClass() != TopLevelWindow.class) {
			return false;
		}

		if (!determinePageType(webResponse).equals(PageType.HTML)) {
			return false;
		}

		return true;
	}

	// WebClient의 private 메서드 addDefaultHeaders를 호출하기 위해 리플렉션 사용
	private void callAddDefaultHeaders(final WebRequest webRequest) {
		Class webClientClass = WebClient.class;

		try {
			Method meth = webClientClass.getDeclaredMethod("addDefaultHeaders", WebRequest.class);
			meth.setAccessible(true);
			meth.invoke(this, webRequest);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
	}

	@Override
	public void destroy() throws Exception {
		close();
	}
}
