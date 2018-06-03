# EVE Swagger Interface (ESI) Proxy

The module provides two servlets which together create a service for exposing a key/hash scheme
for access to OAuth scoped [ESI](https://esi.tech.ccp.is/) endpoints.  The ESI is a service provided
by [CCP](https://www.ccpgames.com/) which allows third party applications to access in game
data for [EVE Online](https://www.eveonline.com/).

Direct access to the ESI normally requires authenticating via OAuth.  This can be inconvenient for
certain applications (see below).  The two servlets in this module together provide a proxy
which accepts "key" and "hash" query parameters which are then translated into an OAuth access token
before forwarding the request to the ESI.  As a result, a much simpler authentication flow can
be used for access to ESI data. 

## Why do we need this proxy?

Originally, CCP provided an [XML API](https://eveonline-third-party-documentation.readthedocs.io/en/latest/xmlapi/index.html)
for accessing in game data.  This API used a simple key and hash to authenticate users.  The simplicity of the
scheme made the API easy to use from many different applications, but not very secure.  Therefore, CCP
decided to improve security by moving to an OAuth scheme for authenticating user access.  This was deployed first for
[CREST](https://eveonline-third-party-documentation.readthedocs.io/en/latest/crest/index.html) and
was used again for the [ESI](https://esi.tech.ccp.is/) which is now the default supported
API for access to game data from EVE Online third party applications.  The XML API and CREST have been officially
retired as of May 2018.

Endpoint security is now better, but the OAuth flow and the need to periodically refresh tokens is inconvenient for certain use
cases, such as:

* Applications which can't or don't want to implement OAuth token refresh logic (e.g. non-interactive applications); or,
* Tools which don't support OAuth authentication for data sources (e.g. Google Sheets).

For these use cases, the key/hash scheme is easier to use.

## How does this work?

The proxy provides the same Swagger-based interface used by the ESI servers, except that the usual OAuth authentication mechanism is 
replaced with a "key and hash" scheme.  That is, you can call the usual ESI endpoints on the proxy, but instead of passing an authorization 
header with an access token, you instead pass two query arguments:

* esiProxyKey - a numeric key; and,
* esiProxyHash - an alphanumeric string.

The proxy uses these arguments to look up a properly refreshed access token which is then added to the call and forwarded to the ESI servers.
The result of the ESI call is passed back to the proxy client.  As long as your access tokens are not revoked, you'll be able to use your 
key/hash pair to access ESI endpoints.

## No OAuth?  How can I protect access to my data?

The "key and hash" scheme used by the proxy is identical to the scheme used by the XML API endpoints and has the same security weakness, 
namely that anyone who has a copy of your key and hash can access any of your authenticated ESI endpoints (using this proxy). There are 
two ways you can protect against unintended use:

1. You can choose to limit the set of ESI scopes accessible to a key/hash pair. This is done at key creation time; or,
2. You can set an expiry date on a key/hash pair which disables the pair after a specified date.

The proxy retains your access token and attempts to refresh it until the key/hash pair expires. Any authorized third party application can do 
the same. So the proxy is no more or less safe than other third party applications which ask you to authenticate scopes.

If all else fails, you can directly revoke any access tokens you've granted to the proxy at the [third party support site](https://community.eveonline.com/support/third-party-applications/).

## How do I use the proxy?

Once you standup an instance of the proxy (see instructions below), you can configure access as follows:

1. Log in to the proxy website (this is standard EVE OAuth authentication using your configured client and secret key).
2. Go to the "Connections" page and create a connection (you'll specify scopes and authenticate when you create the connection).

For each connection you create, the proxy will attempt to keep the access token refreshed each time you try to use the token (assuming the
connection has not expired).

When you're ready to connect to the ESI, you'll use the proxy address in place of the ESI address.  That is, you'll replace `https://esi.tech.ccp.is/`
with `https://your.proxys.address/`.  The proxy supports all the same endpoints as the ESI.  So for example `https://esi.tech.ccp.is/latest/alliances/?datasource=tranquility`
becomes `https://your.proxys.address/latest/alliances/?datasource=tranquility`.

If you're using an authenticated endpoint, you'll need to pass the appropriate key and hash from one of your connections.  For example
`https://your.proxys.address/latest/characters/12345/assets/?datasource=tranquility&esiProxyKey=..yourkey..&esiProxyHash=..yourhash..`

If you're generating a Swagger client, you'll need to make a similar replacement to generate the proper URL for swagger.json.  For example
`https://esi.tech.ccp.is/latest/swagger.json?datasource=tranquility` becomes 
`https://your.proxys.address/latest/swagger.json?datasource=tranquility`.

Currently, the proxy supports all three available ESI servers ("legacy", "latest" and "dev"). However, we only regularly test against "latest".

## Setting up your own proxy

If you want to stand up your own proxy, you can do so on a relatively modest machine.  The steps are:

1. Obtain an EVE SSO OAuth client key and secret at the [EVE Developers site](https://developers.eveonline.com/).  The callback URL for your key will depend on how you configure
   the proxy (see below).  The proxy expects the callback URL to be `${enterprises.orbital.basepath}/${enterprises.orbital.appname}/api/ws/callback/eve`.  If you follow the
   instructions below and use the defaults, then the callback URL will end up as `http://localhost:8080/esi-proxy/api/ws/callback/eve`.
2. Create a database instance using your favorite JDBC and Hibernate compatible SQL database.  By default, the proxy assumes you are using MySQL.  You can change
   to a different database vendor by using the appropriate Hibernate JDBC connector and modifying the JDBC driver settings in `/src/main/resources/META-INF/persistence.xml`.
   You'll also need to create appropriate proxy tables on your database instance.  This can be done in one of two ways:
   1. Create the tables using the sample_schema.sql file in the top level directory of this module.  You may need to customize this file according to the format
      expected by your database vendor; or,
   2. Let Hibernate create tables for you as needed.  This can be done by adding `<property name="hibernate.hbm2ddl.auto" value="create"/>` to your persistence.xml file.
      **NOTE:** be careful with table name case if you develop on MySQL on both Windows and Linux (as I do).  Windows table names are case insensitive, but case matters on Linux.
      In order to make things work correctly, I set "lower_case_table_names = 1" in my.cnf on Linux MySQL.
3. Follow the instructions below to create a proxy instance with access to your database instance.  You'll want to verify authentication works correctly with EVE SSO Auth.
   You can find instructions for that on CCP's site [here](https://eveonline-third-party-documentation.readthedocs.io/en/latest/sso/index.html).

If everything has worked up to this point, then you now have a complete standalone instance of the proxy.

# Building the proxy

## Configuration

The proxy requires the setting and substitution of several parameters which control authentication, database and servlet settings.  Since the frontend is normally built
with [Maven](http://maven.apache.org), configuration is handled by setting or overriding properties in your local Maven settings.xml file.  The following configurations
parameters should be set:

| Parameter | Meaning |
|-----------|---------|
|enterprises.orbital.auth.eve_client_id|EVE Online SSO authentication client ID|
|enterprises.orbital.auth.eve_secret_key|EVE Online SSO authentication secret|
|enterprises.orbital.db.properties.url|Hibernate JDBC connection URL|
|enterprises.orbital.db.properties.user|Hibernate JDBC connection user name|
|enterprises.orbital.db.properties.password|Hibernate JDBC connection password|
|enterprises.orbital.db.properties.driver|Hibernate JDBC driver.  Only necessary to change if you're not using MySQL|
|enterprises.orbital.db.properties.dialect|Hibernate JDBC dialec.  Only necessary to change if you're not using MySQL|
|enterprises.orbital.swaggerui.model|URL for the proxy swagger config, e.g. https://your.proxys.address/api/swagger.json|
|enterprises.orbital.basepath|The base location where the servlet is hosted, e.g. http://localhost:8080|
|enterprises.orbital.appname|Name of the servlet when deployed, e.g. esi-proxy|
|enterprises.orbital.proxyHost|Proxy host (used to substitute in ESI swagger.json), e.g. your.proxys.address|
|enterprises.orbital.proxyPort|Proxy port (used to substitute in ESI swagger.json), e.g. 443|

Proxy authentication settings follow the conventions in the [Orbital OAuth](https://github.com/OrbitalEnterprises/orbital-oauth) module.

To make debugging easier, we've added the property `enterprises.orbital.auth.eve_debug_mode`.  When set to true, authenticating with EVE Online will always succeed
(EVE Online login servers are never actually invoked), and the logged in user will be named "eveuser".  This mode allows you to develop and test when you're not
connected to a network, or otherwise don't want to have to go through the usual authentication flow every time.

At build and deploy time, the parameters above are substituted into the following files:

* src/main/resources/META-INF/persistence.xml
* src/main/resources/ESIProxy.properties
* src/main/webapp/WEB-INF/web.xml

If you are not using Maven to build, you'll need to substitute these settings manually.

## Build

We use [Maven](http://maven.apache.org) to build this module.  Proxy dependencies are released and published to [Maven Central](http://search.maven.org/),
but we don't release the proxy itself.  Instead, clone this repository and use "mvn install".  Make sure you have set all required configuration parameters 
before building (as described in the previous section).

## Deployment

This project is designed to easily deploy in a standard Servlet container.  Two parameters need to be substituted in the web.xml file in order for deployment to work correctly:

| Parameter | Meaning |
|-----------|---------|
|enterprises.orbital.basepath|The base location where the servlet is hosted, e.g. http://localhost:8080|
|enterprises.orbital.appname|Name of the servlet when deployed|

If you follow the configuration and build instructions above, these parameters will be substituted for you.  These settings are used to define the base path for
the REST API endpoints (via Swagger) needed for the frontend.

### Deploying to Tomcat

The default pom.xml in the project includes the [Tomcat Maven plugin](http://tomcat.apache.org/maven-plugin.html) which makes it easy to deploy directly to a Tomcat instance.
This is normally done by adding two stanzas to your settings.xml:

```xml
<servers>
  <server>
    <id>LocalTomcatServer</id>
    <username>admin</username>
    <password>password</password>
  </server>    
</servers>

<profiles>
  <profile>
    <id>LocalTomcat</id>
    <properties>
      <enterprises.orbital.tomcat.url>http://127.0.0.1:8080/manager/text</enterprises.orbital.tomcat.url>
      <enterprises.orbital.tomcat.server>LocalTomcatServer</enterprises.orbital.tomcat.server>
      <enterprises.orbital.tomcat.path>/esi-proxy</enterprises.orbital.tomcat.path>
    </properties>	
  </profile>
</profiles>
```

The first stanza specifies the management credentials for your Tomcat instance.  The second stanza defines the properties needed to install into the server you just defined.
Make sure that `enterprises.orbital.tomcat.path` agrees with `enterprises.orbital.appname` as the former determines the path for your servlet.
With these settings, you can deploy to your Tomcat instance as follows (this example uses Tomcat 7):

```
mvn -P LocalTomcat tomcat7:deploy
```

If you've already deployed, use "redploy" instead.  See the [Tomcat Maven plugin documentation](http://tomcat.apache.org/maven-plugin-2.2/) for more details on how the deployment plugin works.

## Getting Help

The best way to get help is to raise an issue in our [GitHub project](https://github.com/OrbitalEnterprises/orbital-esi-proxy).
