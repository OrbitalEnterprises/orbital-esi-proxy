package enterprises.orbital.esi.proxy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.ssl.SSLContexts;
import org.mitre.dsmiley.httpproxy.ProxyServlet;
import org.mitre.dsmiley.httpproxy.URITemplateProxyServlet;

import com.github.scribejava.core.model.OAuth2AccessToken;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.oauth.EVEAuthHandler;

/**
 * Proxy for requests to CCP ESI endpoints. The proxy handles three types of request:
 * 
 * <ol>
 * <li>Requests for swagger.json are intercepted on return from the ESI and translated, replacing the OAuth security definitions on the ESI endpoint, with the
 * api keys used by the proxy.
 * <li>Requests which include the proxies key/hash query parameters are used to lookup a registered proxy key. If such a key exists, then the proxy extracts the
 * related ESI access token, refreshes it as necessary, and adds it to the header of the request. The request is then forwarded to the ESI and the result is
 * passed through to the caller.
 * <li>All other requests are simply passed through to the ESI, with the results passed back to the caller.
 * </ol>
 * 
 * This class was written using https://github.com/mitre/HTTP-Proxy-Servlet/blob/master/src/main/java/org/mitre/dsmiley/httpproxy/URITemplateProxyServlet.java
 * as a template.
 * 
 */
public class ESIProxyServlet extends ProxyServlet {
  private static final long   serialVersionUID      = 8393680443086268080L;

  private static final String PROP_PROXY_HOST       = "enterprises.orbital.proxyHost";
  private static final String PROP_PROXY_PORT       = "enterprises.orbital.proxyPort";
  private static final String PROP_PROXY_KEY_NAME   = "enterprises.orbital.esi.proxy.keyName";
  private static final String PROP_PROXY_HASH_NAME  = "enterprises.orbital.esi.proxy.hashName";
  private static final String PROP_TRUST_STORE      = "enterprises.orbital.esi.trustStore";
  private static final String PROP_TRUST_STORE_PASS = "enterprises.orbital.esi.trustPass";
  private static final String PROP_APP_NAME         = "enterprises.orbital.appname";
  private static final String PROP_EXPIRY_WINDOW    = "enterprises.orbital.expiryWindow";
  private static final String PROP_ESI_HOME         = "enterprises.orbital.esi.home";
    
  private static final String DEF_PROXY_HOST        = "localhost";
  private static final int    DEF_PROXY_PORT        = 8080;
  private static final String DEF_PROXY_KEY_NAME    = "esiProxyKey";
  private static final String DEF_PROXY_HASH_NAME   = "esiProxyHash";
  private static final String DEF_APP_NAME          = "";
  private static final long   DEF_EXPIRY_WINDOW     = TimeUnit.MILLISECONDS.convert(3, TimeUnit.MINUTES);
  private static final String DEF_ESI_HOME          = "esi.evetech.net";
  private static final String ATTR_QUERY_STRING     = URITemplateProxyServlet.class.getSimpleName() + ".queryString";
  private static final String ATTR_SWAGGER_CONFIG   = ESIProxyServlet.class.getSimpleName() + ".swaggerConfig";
  private static final String ATTR_AUTH_HEADER      = ESIProxyServlet.class.getSimpleName() + ".authHeader";

  protected String            proxyHost;
  protected int               proxyPort;
  protected String            proxyKeyName;
  protected String            proxyHashName;
  protected String            proxySecurityDefinition;
  protected String            proxySecurity;
  protected String            servletPath;
  protected long              expiryWindow;
  protected String            esiHome;

  /**
   * Setup.
   */
  @Override
  protected void initTarget() throws ServletException {
    // Configure location of ESI
    esiHome = OrbitalProperties.getGlobalProperty(PROP_ESI_HOME, DEF_ESI_HOME);
    // Host and port represent the local host and port for the proxy
    proxyHost = OrbitalProperties.getGlobalProperty(PROP_PROXY_HOST, DEF_PROXY_HOST);
    proxyPort = (int) OrbitalProperties.getLongGlobalProperty(PROP_PROXY_PORT, DEF_PROXY_PORT);
    // Names of key and hash query parameters we expect
    proxyKeyName = OrbitalProperties.getGlobalProperty(PROP_PROXY_KEY_NAME, DEF_PROXY_KEY_NAME);
    proxyHashName = OrbitalProperties.getGlobalProperty(PROP_PROXY_HASH_NAME, DEF_PROXY_HASH_NAME);
    // Security definition used to replace OAuth security definition on ESI swagger.json
    proxySecurityDefinition = "\"securityDefinitions\":{ \"" + proxyKeyName + "\" : { \"type\" : \"apiKey\", \"name\" : \"" + proxyKeyName
        + "\", \"in\" : \"query\"}, \"" + proxyHashName + "\" : { \"type\" : \"apiKey\", \"name\" : \"" + proxyHashName + "\", \"in\" : \"query\"}}";
    proxySecurity = "\"security\":[{\"" + proxyKeyName + "\":\\[\\], \"" + proxyHashName + "\":\\[\\]\\}\\]";
    // Local servlet path
    servletPath = OrbitalProperties.getGlobalProperty(PROP_APP_NAME, DEF_APP_NAME);
    // Maximum window (in milliseconds) between now and token expiry time. If we're within the window, then
    // we'll refresh the token before making a request.
    expiryWindow = OrbitalProperties.getLongGlobalProperty(PROP_EXPIRY_WINDOW, DEF_EXPIRY_WINDOW);
    // If your setup requires a specific trust store you can configure that here.
    if (!OrbitalProperties.getGlobalProperty(PROP_TRUST_STORE, "").isEmpty()) {
      System.setProperty("javax.net.ssl.trustStore", OrbitalProperties.getGlobalProperty(PROP_TRUST_STORE));
      if (!OrbitalProperties.getGlobalProperty(PROP_TRUST_STORE_PASS, "").isEmpty())
        System.setProperty("javax.net.ssl.trustStorePassword", OrbitalProperties.getGlobalProperty(PROP_TRUST_STORE_PASS));
      SSLContext sslcontext = SSLContexts.createSystemDefault();
      RegistryBuilder.<ConnectionSocketFactory> create().register("http", PlainConnectionSocketFactory.INSTANCE)
          .register("https", new SSLConnectionSocketFactory(sslcontext)).build();
    }
  }

  /**
   * Main proxy request entry point.
   * 
   * @param servletRequest
   *          request from client
   * @param servletResponse
   *          response we'll send back to the client
   */
  @Override
  protected void service(
                         HttpServletRequest servletRequest,
                         HttpServletResponse servletResponse)
    throws ServletException, IOException {

    // Extract the last part of the servlet path as this contains the target ESI server (e.g. latest, legacy, dev)
    // This isn't part of path info because of the way the mappings are configured in web.xml
    String contextPath = servletRequest.getServletPath();
    contextPath = contextPath.substring(contextPath.lastIndexOf('/'));

    // Detect first case which will have a URL like this:
    //
    // https://esi.tech.ccp.is/latest/swagger.json?datasource=tranquility
    //
    String pathPart = servletRequest.getPathInfo();
    if (pathPart.endsWith("swagger.json")) {
      log("Intercepting swagger.json");
      // Forward then translate the result
      servletRequest.setAttribute(ATTR_TARGET_HOST, new HttpHost(esiHome, 443, "https"));
      servletRequest.setAttribute(ATTR_TARGET_URI, contextPath);
      servletRequest.setAttribute(ATTR_SWAGGER_CONFIG, true);
      super.service(servletRequest, servletResponse);
      return;
    }

    // Else, check whether there are key/hash params. See
    // https://github.com/mitre/HTTP-Proxy-Servlet/blob/master/src/main/java/org/mitre/dsmiley/httpproxy/URITemplateProxyServlet.java
    // for an explanation of why we don't just use servletRequest.getParameter here.
    String rawQueryString = servletRequest.getQueryString();
    String queryString = "?" + (rawQueryString == null ? "" : rawQueryString);// no "?" but might have "#"
    int hash = queryString.indexOf('#');
    if (hash >= 0) queryString = queryString.substring(0, hash);
    List<NameValuePair> pairs;
    try {
      pairs = URLEncodedUtils.parse(new URI(queryString), "UTF-8");
    } catch (URISyntaxException e) {
      servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unexpected URI parsing error on " + queryString);
      return;
    }
    LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
    for (NameValuePair pair : pairs) {
      params.put(pair.getName(), pair.getValue());
    }

    // Look for the ESI proxy key/hash pair and process
    if (params.containsKey(proxyKeyName) && params.containsKey(proxyHashName)) {
      // Extract and remove params
      long pKey = -1;
      try {
        pKey = Long.valueOf(params.get(proxyKeyName));
      } catch (NumberFormatException e) {
        servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad proxy key: " + params.get(proxyKeyName));
        return;
      }
      String pHash = params.get(proxyHashName);
      params.remove(proxyKeyName);
      params.remove(proxyHashName);
      // Attempt to map to a ProxyAccessKey
      ProxyAccessKey connKey = null;
      try {
        connKey = ProxyAccessKey.checkHash(pKey, pHash);
        if (connKey == null) {
          servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Incorrect hash for key pair (" + pKey + ", " + pHash + ")");
          return;
        }
      } catch (NoSuchKeyException e) {
        servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "No connection found with proxy key: " + pKey);
        return;
      }
      // Verify this access key has not expired
      if (connKey.getExpiry() > 0 && connKey.getExpiry() < OrbitalProperties.getCurrentTime()) {
        servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Proxy key expired");
        return;
      }
      // Ensure the access token is valid, if not attempt to renew it
      if (connKey.getAccessTokenExpiry() - OrbitalProperties.getCurrentTime() < expiryWindow) {
        // TODO: we'll need to be more careful here if we deploy this to a cluster behind a load balancer
        String refreshToken = connKey.getRefreshToken();
        if (refreshToken == null) {
          servletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Connection does not have a valid refresh token.  Please delete and re-create.");
          return;
        }
        String eveClientID = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_client_id");
        String eveSecretKey = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_secret_key");
        OAuth2AccessToken newToken = EVEAuthHandler.doRefresh(eveClientID, eveSecretKey, refreshToken);
        if (newToken == null) {
          servletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Failed to refresh token.  You'll need to delete connection and re-create.");
          return;
        }
        connKey.setAccessToken(newToken.getAccessToken());
        connKey.setAccessTokenExpiry(OrbitalProperties.getCurrentTime() + TimeUnit.MILLISECONDS.convert(newToken.getExpiresIn(), TimeUnit.SECONDS));
        connKey.setRefreshToken(newToken.getRefreshToken());
        connKey = ProxyAccessKey.update(connKey);
        if (connKey == null) {
          servletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Failed to refresh token.  You'll need to delete connection and re-create.");
          return;
        }
      }
      // Attach the access token to the authorization header
      String header = "Bearer " + connKey.getAccessToken();
      servletRequest.setAttribute(ATTR_AUTH_HEADER, header);
    }

    // Re-attach query string and forward request
    servletRequest.setAttribute(ATTR_TARGET_HOST, new HttpHost(esiHome, 443, "https"));
    servletRequest.setAttribute(ATTR_TARGET_URI, contextPath);

    // Determine the new query string based on removing the used names
    StringBuilder newQueryBuf = new StringBuilder(queryString.length());
    for (Map.Entry<String, String> nameVal : params.entrySet()) {
      if (newQueryBuf.length() > 0) newQueryBuf.append('&');
      newQueryBuf.append(nameVal.getKey()).append('=');
      if (nameVal.getValue() != null) newQueryBuf.append(nameVal.getValue());
    }
    servletRequest.setAttribute(ATTR_QUERY_STRING, newQueryBuf.toString());

    super.service(servletRequest, servletResponse);
  }

  /**
   * Override to provide our modified query string.
   * 
   * @param servletRequest
   *          original servlet request
   * @param queryString
   *          original query string
   * @return query string to attach
   */
  @Override
  protected String rewriteQueryStringFromRequest(
                                                 HttpServletRequest servletRequest,
                                                 String queryString) {
    return (String) servletRequest.getAttribute(ATTR_QUERY_STRING);
  }

  // Pattern matchers for elements of swagger.json we need to replace
  //
  // "basePath":"/latest"
  protected static final Pattern BASEPATH_PATTERN = Pattern.compile("\"basePath\"[ ]*:[ ]*\"([a-zA-Z/]+)\"");
  // "host":"esi.tech.ccp.is"
  protected static final Pattern HOSTNAME_PATTERN = Pattern.compile("\"host\"[ ]*:[ ]*\"([a-zA-Z0-9.]+)\"");
  // "schemes":["https"]
  protected static final Pattern SCHEMES_PATTERN  = Pattern.compile("\"schemes\"[ ]*:[ ]*\\[\"https\"\\]");
  // "securityDefinitions":{ "evesso":{ ... "type":"oauth2" }}
  protected static final Pattern SECDEF_PATTERN   = Pattern.compile("\"securityDefinitions\"[ ]*:.*\"type\"[ ]*:[ ]*\"oauth2\"[ ]*\\}\\}");
  // "security":[{"evesso":["esi-assets.read_assets.v1"]}]
  protected static final Pattern SECURITY_PATTERN = Pattern.compile("\"security\"[ ]*:[ ]*\\[\\{\"evesso\".*?\\]\\}\\]");

  /**
   * Override response entity to give us a chance to replace content.
   * 
   * @param proxyResponse
   *          response returned from proxied service
   * @param servletResponse
   *          response to be sent to client
   * @param proxyRequest
   *          request we sent to proxied service
   * @param servletRequest
   *          request sent from client
   */
  @Override
  protected void copyResponseEntity(
                                    HttpResponse proxyResponse,
                                    HttpServletResponse servletResponse,
                                    HttpRequest proxyRequest,
                                    HttpServletRequest servletRequest)
    throws IOException {
    // Pass through if this isn't a swagger.json request
    if (servletRequest.getAttribute(ATTR_SWAGGER_CONFIG) == null) {
      super.copyResponseEntity(proxyResponse, servletResponse, proxyRequest, servletRequest);
      return;
    }

    // Extract config and make the following changes:
    // - Change host to the proxy host
    // - Change schemes to those supported by the proxy
    // - Replace securityDefinitions with esiProxyKey and esiProxyHash schemes
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      Header checkGzip = proxyResponse.getFirstHeader("Content-Encoding");
      boolean useGzip = checkGzip != null && checkGzip.getValue().equals("gzip");
      StringBuilder assembly = new StringBuilder();
      BufferedReader extractor = new BufferedReader(new InputStreamReader(useGzip ? new GZIPInputStream(entity.getContent()) : entity.getContent()));
      for (String next = extractor.readLine(); next != null; next = extractor.readLine())
        assembly.append(next);
      // Fix hostname
      StringBuffer transformed = new StringBuffer();
      String src = assembly.toString();
      Matcher matcher = HOSTNAME_PATTERN.matcher(src);
      if (matcher.find()) {
        String replacement = "\"host\": \"" + proxyHost;
        if (proxyPort != 80 && proxyPort != 443) {
          replacement += ":" + proxyPort;
        }
        replacement += "\"";
        matcher.appendReplacement(transformed, replacement);
      }
      matcher.appendTail(transformed);
      // Fix basepath
      if (servletPath != null && servletPath.length() > 0) {
        src = transformed.toString();
        transformed = new StringBuffer();
        matcher = BASEPATH_PATTERN.matcher(src);
        if (matcher.find()) {
          String replacement = "\"basePath\":\"/" + servletPath + matcher.group(1) + "\"";
          matcher.appendReplacement(transformed, replacement);
        }
        matcher.appendTail(transformed);
      }
      // Fix scheme
      src = transformed.toString();
      transformed = new StringBuffer();
      matcher = SCHEMES_PATTERN.matcher(src);
      if (matcher.find()) {
        String replacement = "\"schemes\": [";
        switch (proxyPort) {
        case 443:
          replacement += "\"https\"";
          break;
        case 80:
        default:
          replacement += "\"http\"";
          break;
        }
        replacement += "]";
        matcher.appendReplacement(transformed, replacement);
      }
      matcher.appendTail(transformed);
      // Fix securityDefinitions
      src = transformed.toString();
      transformed = new StringBuffer();
      matcher = SECDEF_PATTERN.matcher(src);
      if (matcher.find()) matcher.appendReplacement(transformed, proxySecurityDefinition);
      matcher.appendTail(transformed);
      // Fix security instances
      src = transformed.toString();
      transformed = new StringBuffer();
      matcher = SECURITY_PATTERN.matcher(src);
      while (matcher.find()) {
        matcher.appendReplacement(transformed, proxySecurity);
      }
      matcher.appendTail(transformed);
      // Write new output
      ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
      BufferedWriter generator = new BufferedWriter(new OutputStreamWriter(useGzip ? new GZIPOutputStream(bytesOut) : bytesOut));
      generator.write(transformed.toString());
      generator.close();
      HttpEntity transformedEntity = new InputStreamEntity(new ByteArrayInputStream(bytesOut.toByteArray()));
      OutputStream servletOutputStream = servletResponse.getOutputStream();
      transformedEntity.writeTo(servletOutputStream);
    }
  }

  /**
   * Override to place authorization header when needed.
   * 
   * @param servletRequest
   *          client request
   * @param proxyRequest
   *          request we'll send to proxied service
   */
  @Override
  protected void copyRequestHeaders(
                                    HttpServletRequest servletRequest,
                                    HttpRequest proxyRequest) {
    // Superclass handles all headers except Authorization
    super.copyRequestHeaders(servletRequest, proxyRequest);
    if (servletRequest.getAttribute(ATTR_AUTH_HEADER) != null)
      proxyRequest.addHeader("Authorization", String.valueOf(servletRequest.getAttribute(ATTR_AUTH_HEADER)));
  }

}
