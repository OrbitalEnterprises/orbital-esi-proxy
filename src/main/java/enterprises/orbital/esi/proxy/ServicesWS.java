package enterprises.orbital.esi.proxy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.http.client.utils.URIBuilder;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.base.Stamper;
import enterprises.orbital.db.ConnectionFactory.RunInTransaction;
import enterprises.orbital.oauth.AuthUtil;
import enterprises.orbital.oauth.EVEApi;
import enterprises.orbital.oauth.EVEAuthHandler;
import enterprises.orbital.oauth.EVECallbackHandler;
import enterprises.orbital.oauth.LogoutHandler;
import enterprises.orbital.oauth.UserAccount;
import enterprises.orbital.oauth.UserAuthSource;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * API for authentication, authentication with scopes, creating connection keys, and and logging out.
 */
@Path("/ws")
@Produces({
    "application/json"
})
@Api(
    tags = {
        "Services"
    },
    produces = "application/json")
public class ServicesWS {
  private static final Logger log                      = Logger.getLogger(ServicesWS.class.getName());
  public static final String  PROP_APP_PATH            = "enterprises.orbital.apppath";
  public static final String  DEF_APP_PATH             = "http://localhost/esi-proxy";
  public static final String  PROP_BUILD_DATE          = "enterprises.orbital.esi.proxy.build";
  public static final String  PROP_VERSION             = "enterprises.orbital.esi.proxy.version";
  public static final String  PROP_TEMP_STATE_LIFETIME = "enterprises.orbital.esi.tempStateLifetime";
  public static final long    DEF_TEMP_STATE_LIFETIME  = TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES);
  public static final String  PROP_KEY_LIMIT           = "enterprises.orbital.esi.keyLimit";
  public static final long    DEF_KEY_LIMIT            = 100;
  public static final String  PROP_RESTRICT_LOGIN      = "enterprises.orbital.esi.restrictLoginToAdmin";
  public static final boolean DEF_RESTRICT_LOGIN       = false;

  // An in-memory object holding new access key state while the user authenticates with the
  // SSO. This data is purged periodically if for some reason the server fails to retrieve
  // it from the internal map (for example, if authentication fails or the user does not complete).
  // TODO: this scheme won't work if we load balance across multiple instances of the proxy. We'll have to use the DB in that case.
  protected static class NewAccessKeyState {
    public long   createTime;
    public long   uid;
    public long   expiry;
    public String serverType;
    public String scopes;

    public NewAccessKeyState(long createTime, long uid, long expiry, String serverType, String scopes) {
      super();
      this.createTime = createTime;
      this.uid = uid;
      this.expiry = expiry;
      this.serverType = serverType;
      this.scopes = scopes;
    }
  }

  // Temporary map of keys awaiting authorization before being added to the system
  protected static final Map<String, NewAccessKeyState> tempStateMap = new HashMap<>();

  // Generate random key for next temp state
  protected static String generateRandomKey() {
    Random rotator = new Random(OrbitalProperties.getCurrentTime());
    StringBuilder entropy = new StringBuilder();
    entropy.append(rotator.nextLong());
    entropy.append(rotator.nextLong());
    entropy.append(rotator.nextLong());
    return Stamper.fastDigest(entropy.toString());
  }

  static {
    // Start a thread to periodically clean up the tempStateMap
    (new Thread(new Runnable() {

      @Override
      public void run() {
        long stateLifetime = OrbitalProperties.getLongGlobalProperty(PROP_TEMP_STATE_LIFETIME, DEF_TEMP_STATE_LIFETIME);
        while (true) {
          try {
            long now = OrbitalProperties.getCurrentTime();
            List<String> toDelete = new ArrayList<String>();
            synchronized (tempStateMap) {
              for (Entry<String, NewAccessKeyState> next : tempStateMap.entrySet()) {
                if (next.getValue().createTime + stateLifetime < now) toDelete.add(next.getKey());
              }
              for (String nextDel : toDelete) {
                tempStateMap.remove(nextDel);
              }
            }
            Thread.sleep(TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES));
          } catch (Throwable e) {
            // Catch everything but log it
            log.log(Level.WARNING, "caught error in state cleanup loop (ignoring)", e);
          }
        }
      }

    })).start();
  }

  protected static URIBuilder makeStandardBuilder(
                                                  HttpServletRequest req)
    throws MalformedURLException, URISyntaxException {
    URIBuilder builder = new URIBuilder(OrbitalProperties.getGlobalProperty(PROP_APP_PATH, DEF_APP_PATH) + "/");
    return builder;
  }

  protected static URIBuilder makeNewAccessKeyBuilder(
                                                      HttpServletRequest req)
    throws MalformedURLException, URISyntaxException {
    URIBuilder builder = new URIBuilder(OrbitalProperties.getGlobalProperty(PROP_APP_PATH, DEF_APP_PATH) + "/#/connections");
    return builder;
  }

  protected static String makeErrorCallback(
                                            HttpServletRequest req,
                                            String source)
    throws MalformedURLException, URISyntaxException {
    return makeGenericErrorCallback(req,
                                    "Error while authenticating with " + source + ".  Please retry.  If the problem perists, please contact the site admin.");
  }

  protected static String makeGenericErrorCallback(
                                                   HttpServletRequest req,
                                                   String msg)
    throws MalformedURLException, URISyntaxException {
    URIBuilder builder = makeStandardBuilder(req);
    builder.addParameter("auth_error", msg);
    return builder.toString();
  }

  protected static void loginDebugUser(
                                       String source,
                                       String screenName,
                                       HttpServletRequest req)
    throws IOException {
    UserAccount existing = AuthUtil.getCurrentUser(req);
    UserAuthSource authSource = AuthUtil.getBySourceScreenname(source, screenName);
    if (authSource != null) {
      // Already exists
      if (existing != null) {
        // User already signed in so change the associated to the current user. There may also be a redirect we should prefer.
        authSource.updateAccount(existing);
      } else {
        // Otherwise, sign in as usual.
        AuthUtil.signOn(req, authSource.getOwner(), authSource);
      }
    } else {
      // New user unless already signed in, in which case it's a new association.
      UserAccount newUser = existing == null ? AuthUtil.createNewUserAccount(false) : existing;
      authSource = AuthUtil.createSource(newUser, source, screenName, "debug user");
      if (existing == null) {
        // New user needs to sign in.
        AuthUtil.signOn(req, newUser, authSource);
      }
    }
  }

  @Path("/login/{source}")
  @GET
  @ApiOperation(
      value = "Authenticate using a specified source.",
      notes = "Initiate authentication using the specified source.  This will most often trigger a redirection to OAuth.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 307,
              message = "Temporary redirect to an OAuth endpoint to initiate authentication.")
      })
  public Response login(
                        @Context HttpServletRequest req,
                        @PathParam("source") @ApiParam(
                            name = "source",
                            required = true,
                            value = "The source with which to authenticate.") String source)
    throws IOException, URISyntaxException {
    String redirect;
    URIBuilder builder = makeStandardBuilder(req);

    switch (source) {
    case "eve":
      if (OrbitalProperties.getBooleanGlobalProperty("enterprises.orbital.auth.eve_debug_mode", false)) {
        // In this case, skip the usual login scheme and immediately log in the user with a debug user.
        // This mode is normally only enabled for local test since EVE OAuth login doesn't work in that case.
        String eveDebugUser = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_debug_user", "eveuser");
        loginDebugUser("eve", eveDebugUser, req);
        URIBuilder connectionsBuilder = makeNewAccessKeyBuilder(req);
        return Response.temporaryRedirect(new URI(connectionsBuilder.toString())).build();
      } else {
        String eveClientID = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_client_id");
        String eveSecretKey = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_secret_key");
        builder.setPath(builder.getPath() + "api/ws/callback/eve");
        redirect = EVEAuthHandler.doGet(eveClientID, eveSecretKey, builder.toString(), null, null, req);
        if (redirect == null) redirect = makeErrorCallback(req, "EVE");
        log.fine("Redirecting to: " + redirect);
        return Response.temporaryRedirect(new URI(redirect)).build();
      }

    default:
      // Log but otherwise ignore.
      log.severe("Unrecognized login source: " + source);
    }

    return null;
  }

  @Path("/callback/{source}")
  @GET
  @ApiOperation(
      value = "Handle OAuth callback for specified source.",
      notes = "Handle OAuth callback after initial redirection to an OAuth source.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 307,
              message = "Temporary redirect back to Jeeves site."),
          @ApiResponse(
              code = 400,
              message = "Unable to complete authentication.")
      })
  public Response callback(
                           @Context HttpServletRequest req,
                           @PathParam("source") @ApiParam(
                               name = "source",
                               required = true,
                               value = "The source with which authentication just completed.") String source)
    throws IOException, URISyntaxException {
    String redirect;
    URIBuilder builder = makeNewAccessKeyBuilder(req);

    switch (source) {
    case "eve":
      String eveClientID = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_client_id");
      String eveSecretKey = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_secret_key");
      String eveVerifyURL = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_verify_url");
      redirect = handleNewAccessKeyCallback(eveClientID, eveSecretKey, eveVerifyURL, builder.toString(), req);
      if (redirect != null)
        // We handled this request in the new access key handler. Return the redirect.
        return Response.temporaryRedirect(new URI(redirect)).build();
      // Otherwise, continue to process as if this is a regular login request
      redirect = EVECallbackHandler.doGet(eveClientID, eveSecretKey, eveVerifyURL, builder.toString(), req);
      if (redirect == null) redirect = makeErrorCallback(req, "EVE");
      log.fine("Redirecting to: " + redirect);
      return Response.temporaryRedirect(new URI(redirect)).build();

    default:
      // Log but otherwise ignore.
      log.severe("Unrecognized callback source: " + source);
    }

    redirect = makeErrorCallback(req, "Unknown Scheme");
    return Response.temporaryRedirect(new URI(redirect)).build();
  }

  @Path("/logout")
  @GET
  @ApiOperation(
      value = "Logout the current logged in user.",
      notes = "Logout the current logged in user.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 307,
              message = "Temporary redirect back to Jeeves site.")
      })
  public Response logout(
                         @Context HttpServletRequest req)
    throws IOException, URISyntaxException {
    URIBuilder builder = makeStandardBuilder(req);
    String redirect = LogoutHandler.doGet(null, builder.toString(), req);
    // This should never happen for the normal logout case.
    assert redirect != null;
    log.fine("Redirecting to: " + redirect);
    return Response.temporaryRedirect(new URI(redirect)).build();
  }

  @Path("/build_date")
  @GET
  @ApiOperation(
      value = "Return the build date of the current release")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "Build date of current release",
              response = String.class)
      })
  public Response buildDate() {
    return Response.ok().entity(new Object() {
      @SuppressWarnings("unused")
      public final String buildDate = OrbitalProperties.getGlobalProperty(PROP_BUILD_DATE, "unknown");
    }).build();
  }

  @Path("/version")
  @GET
  @ApiOperation(
      value = "Return the version of the current release")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "Version of current release",
              response = String.class)
      })
  public Response version() {
    return Response.ok().entity(new Object() {
      @SuppressWarnings("unused")
      public final String version = OrbitalProperties.getGlobalProperty(PROP_VERSION, "unknown");
    }).build();
  }

  @Path("/user_last_source/{uid}")
  @GET
  @ApiOperation(
      value = "Get the last user auth source used by the given user, or the currently logged in user",
      notes = "The last user auth source for the specified user, or null if the user is not logged in")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "last user auth source, or null",
              response = ProxyUserAuthSource.class),
          @ApiResponse(
              code = 401,
              message = "requesting source for other than local user, but requestor not an admin",
              response = ServiceError.class),
          @ApiResponse(
              code = 404,
              message = "requesting source for other than local user, but specified user not found",
              response = ServiceError.class),
          @ApiResponse(
              code = 500,
              message = "Internal account service service error",
              response = ServiceError.class),
      })
  public Response getUserLastSource(
                                    @Context HttpServletRequest request,
                                    @PathParam("uid") @ApiParam(
                                        name = "uid",
                                        required = true,
                                        value = "ID of user account for which the last source will be retrieved.  Set to -1 to retrieve for the current logged in user.") long uid) {
    // Retrieve current logged in user
    ProxyUserAccount user = (ProxyUserAccount) AuthUtil.getCurrentUser(request);
    ProxyUserAuthSource src = null;
    // If requesting for other than the logged in user, check admin
    if (user == null || (user.getID() != uid && uid != -1 && !user.isAdmin())) {
      ServiceError errMsg = new ServiceError(
          Status.UNAUTHORIZED.getStatusCode(), "Requesting source for other than local user, but requestor not logged in or not admin");
      return Response.status(Status.UNAUTHORIZED).entity(errMsg).build();
    }
    // If requesting for other than the logged in user, find user
    if (uid != -1) {
      user = ProxyUserAccount.getAccount(uid);
      if (user == null) {
        ServiceError errMsg = new ServiceError(Status.NOT_FOUND.getStatusCode(), "Requesting source for other than local user, but target user not found");
        return Response.status(Status.NOT_FOUND).entity(errMsg).build();
      }
    }
    // If we found an appropriate user, then look up the source
    if (user != null) {
      src = ProxyUserAuthSource.getLastUsedSource(user);
      if (src == null) {
        ServiceError errMsg = new ServiceError(
            Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Error retrieving auth source, please contact the administrator if this error persists");
        return Response.status(Status.INTERNAL_SERVER_ERROR).entity(errMsg).build();
      }
    }
    return Response.ok().entity(src).build();
  }

  @Path("/user")
  @GET
  @ApiOperation(
      value = "Get information about the current logged in user",
      notes = "User information about the current logged in user, or null if no user logged in")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "logged in user, or null",
              response = ProxyUserAccount.class),
      })
  public Response getUser(
                          @Context HttpServletRequest request) {
    // Retrieve current logged in user
    ProxyUserAccount user = (ProxyUserAccount) AuthUtil.getCurrentUser(request);
    return Response.ok().entity(user).build();
  }

  @Path("/access_key")
  @GET
  @ApiOperation(
      value = "Get list of access keys for the currently authenticated user")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "list of access keys",
              response = ProxyAccessKey.class,
              responseContainer = "array"),
          @ApiResponse(
              code = 401,
              message = "user not logged in",
              response = ServiceError.class),
          @ApiResponse(
              code = 500,
              message = "internal service error",
              response = ServiceError.class),
      })
  public Response getAccessKeys(
                                @Context HttpServletRequest request) {
    // Retrieve user and verify as needed
    ProxyUserAccount user = (ProxyUserAccount) AuthUtil.getCurrentUser(request);
    if (user == null) {
      ServiceError errMsg = new ServiceError(Status.UNAUTHORIZED.getStatusCode(), "User not logged in");
      return Response.status(Status.UNAUTHORIZED).entity(errMsg).build();
    }
    // Retrieve keys
    List<ProxyAccessKey> result = new ArrayList<ProxyAccessKey>();
    result = ProxyAccessKey.getAllKeys(user);
    if (result == null) {
      ServiceError errMsg = new ServiceError(
          Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Error retrieving access keys, contact admin if this problem persists");
      return Response.status(Status.INTERNAL_SERVER_ERROR).entity(errMsg).build();
    }
    // Make sure transient fields are properly generated before returning result
    for (ProxyAccessKey nextKey : result) {
      nextKey.generateCredential();
    }
    // Finish
    return Response.ok().entity(result).build();
  }

  @Path("/access_key")
  @POST
  @ApiOperation(
      value = "Create or update an access key.",
      notes = "To create a new key, the posted key should have a kid of -1.  Otherwise, the posted key is used to update the expiry (only) of an existing key.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "access key saved or updated successfully",
              response = ProxyAccessKey.class),
          @ApiResponse(
              code = 400,
              message = "access key contains an illegal value",
              response = ServiceError.class),
          @ApiResponse(
              code = 401,
              message = "user not logged",
              response = ServiceError.class),
          @ApiResponse(
              code = 403,
              message = "illegal value specified in update",
              response = ServiceError.class),
          @ApiResponse(
              code = 404,
              message = "specified key not associated with the logged in user",
              response = ServiceError.class),
          @ApiResponse(
              code = 500,
              message = "internal service error",
              response = ServiceError.class),
      })
  public Response saveAccessKey(
                                @Context HttpServletRequest request,
                                @ApiParam(
                                    name = "key",
                                    required = true,
                                    value = "Access key to save or update") ProxyAccessKey key)
    throws IOException, URISyntaxException {
    // Verify post argument
    if (key == null) {
      ServiceError errMsg = new ServiceError(Status.FORBIDDEN.getStatusCode(), "POST argument is null");
      return Response.status(Status.FORBIDDEN).entity(errMsg).build();
    }
    // Retrieve user and verify as needed
    ProxyUserAccount user = (ProxyUserAccount) AuthUtil.getCurrentUser(request);
    if (user == null) {
      ServiceError errMsg = new ServiceError(Status.UNAUTHORIZED.getStatusCode(), "user not logged in");
      return Response.status(Status.UNAUTHORIZED).entity(errMsg).build();
    }
    // ID on the posted key determines whether this is a new key or an update
    if (key.getKid() == -1) {
      // Ensure user doesn't have too many keys
      int currentKeyCount = ProxyAccessKey.getAllKeys(user).size();
      if (currentKeyCount >= OrbitalProperties.getLongGlobalProperty(PROP_KEY_LIMIT, DEF_KEY_LIMIT)) {
        ServiceError errMsg = new ServiceError(
            Status.UNAUTHORIZED.getStatusCode(),
            "you already have the maximum number of connections which is " + OrbitalProperties.getLongGlobalProperty(PROP_KEY_LIMIT, DEF_KEY_LIMIT));
        return Response.status(Status.UNAUTHORIZED).entity(errMsg).build();
      }
      // New key flow:
      // 1) Create a state object encapsulating request details. This is just a temporary object which will be purged if unused.
      // 2) Redirect the user to EVE SSO with the scopes specified on the posted key and the key to the temporary state.
      // 3) If the user successfully authenticates, then we save the new key with the supplied access token and other key parameters
      // 4) Otherwise, the user will be re-directed back to home page of the proxy.
      // NOTE: if we're in debug mode then just create a fake access key for testing. These should be destroyed as they are completely bogus.
      long expiry = Math.max(-1, key.getExpiry());
      String scopes = key.getScopes();
      String serverType = key.getServerType();
      switch (serverType) {
      case "latest":
      case "legacy":
      case "dev":
        break;
      default:
        ServiceError errMsg = new ServiceError(Status.BAD_REQUEST.getStatusCode(), "unknown server type '" + String.valueOf(serverType) + "'");
        return Response.status(Status.BAD_REQUEST).entity(errMsg).build();
      }
      if (OrbitalProperties.getBooleanGlobalProperty("enterprises.orbital.auth.eve_debug_mode", false)) {
        // Create the key without SSO. This key won't have an access or refresh token, so it's just for testing the UI
        ProxyAccessKey.createKey(user, expiry, serverType, scopes, "fakechar " + OrbitalProperties.getCurrentTime());
      } else {
        // This is a real key, create the temp state
        long now = OrbitalProperties.getCurrentTime();
        String stateKey = generateRandomKey();
        NewAccessKeyState tempState = new NewAccessKeyState(now, user.getID(), expiry, serverType, scopes);
        synchronized (tempStateMap) {
          tempStateMap.put(stateKey, tempState);
        }
        // Start the OAuth flow to authenticate the listed scopes
        String redirect;
        URIBuilder builder = makeStandardBuilder(request);
        String eveClientID = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_client_id");
        String eveSecretKey = OrbitalProperties.getGlobalProperty("enterprises.orbital.auth.eve_secret_key");
        builder.setPath(builder.getPath() + "api/ws/callback/eve");
        redirect = EVEAuthHandler.doGet(eveClientID, eveSecretKey, builder.toString(), scopes, stateKey, request);
        if (redirect == null) redirect = makeErrorCallback(request, "EVE");
        log.fine("Redirecting to: " + redirect);
        final String redirectResult = redirect;
        // Returning a Response.temporary redirect won't work here because this is an AJAX call.
        // Instead, return the redirect as a string. The client will store in window.location to cause the redirect.
        return Response.ok().entity(new Object() {
          @SuppressWarnings("unused")
          public final String newLocation = redirectResult;
        }).build();
      }
    } else {
      // Update - find the key
      ProxyAccessKey existing = ProxyAccessKey.getKeyByID(key.getKid());
      if (existing == null || !existing.getUser().equals(user)) {
        ServiceError errMsg = new ServiceError(Status.NOT_FOUND.getStatusCode(), "Target access key not found");
        return Response.status(Status.NOT_FOUND).entity(errMsg).build();
      }
      // Update from passed value - only expiry can be changed
      ProxyAccessKey.updateKey(user, existing.getKid(), key.getExpiry());
    }
    return Response.ok().build();
  }

  @Path("/access_key/{kid}")
  @DELETE
  @ApiOperation(
      value = "Delete an access key.",
      notes = "Delete the specified access key.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "acces key deleted successfully"),
          @ApiResponse(
              code = 401,
              message = "user not logged in",
              response = ServiceError.class),
          @ApiResponse(
              code = 404,
              message = "no key with the specified ID found",
              response = ServiceError.class),
          @ApiResponse(
              code = 500,
              message = "internal service error",
              response = ServiceError.class),
      })
  public Response deleteAccessKey(
                                  @Context HttpServletRequest request,
                                  @PathParam("kid") @ApiParam(
                                      name = "kid",
                                      required = true,
                                      value = "ID of access key to delete.") long kid) {
    // Retrieve user and verify as needed
    ProxyUserAccount user = (ProxyUserAccount) AuthUtil.getCurrentUser(request);
    if (user == null) {
      ServiceError errMsg = new ServiceError(Status.UNAUTHORIZED.getStatusCode(), "Requestor not logged in");
      return Response.status(Status.UNAUTHORIZED).entity(errMsg).build();
    }
    // Target key required - find it
    ProxyAccessKey key = ProxyAccessKey.getKeyByID(kid);
    if (key == null || !key.getUser().equals(user)) {
      ServiceError errMsg = new ServiceError(Status.NOT_FOUND.getStatusCode(), "Target key not found");
      return Response.status(Status.NOT_FOUND).entity(errMsg).build();
    }
    // Delete and return
    if (!ProxyAccessKey.deleteKey(user, kid)) {
      ServiceError errMsg = new ServiceError(
          Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Internal error deleting access key, contact admin if this problem persists");
      return Response.status(Status.INTERNAL_SERVER_ERROR).entity(errMsg).build();
    }
    return Response.ok().build();
  }

  protected static String handleNewAccessKeyCallback(
                                                     String clientID,
                                                     String secretKey,
                                                     String verifyURL,
                                                     String callback,
                                                     HttpServletRequest req)
    throws IOException, URISyntaxException {

    // This is a new access key request if the state field is present and valid
    String stateKey = req.getParameter("state");
    NewAccessKeyState cachedState = null;
    synchronized (tempStateMap) {
      if (stateKey == null || !tempStateMap.containsKey(stateKey))
        // Missing or invalid state key
        return null;
      // Otherwise, capture the state and cleanup the map
      cachedState = tempStateMap.get(stateKey);
      tempStateMap.remove(stateKey);
    }

    // Resolve the user in the cached state. Error if we can't resolve.
    ProxyUserAccount user = ProxyUserAccount.getAccount(cachedState.uid);
    if (user == null) return null;

    // Check whether we're only allowing admins
    if (OrbitalProperties.getBooleanGlobalProperty(PROP_RESTRICT_LOGIN, DEF_RESTRICT_LOGIN)
        && !user.isAdmin()) { return makeGenericErrorCallback(req, "Only administrative accounts are allowed to create access keys, sorry!"); }

    // Construct the service to use for verification.
    OAuth20Service service = new ServiceBuilder().apiKey(clientID).apiSecret(secretKey).build(EVEApi.instance());

    try {
      // Exchange for access token
      OAuth2AccessToken accessToken = service.getAccessToken(req.getParameter("code"));

      // Retrieve character selected for login. We add this to the access key to make it easier to identify the character associated with each key.
      OAuthRequest request = new OAuthRequest(Verb.GET, verifyURL, service.getConfig());
      service.signRequest(accessToken, request);
      com.github.scribejava.core.model.Response response = request.send();
      if (!response.isSuccessful()) throw new IOException("credential request was not successful!");
      String charName = (new Gson()).fromJson((new JsonParser()).parse(response.getBody()).getAsJsonObject().get("CharacterName"), String.class);

      // Create the new access key.
      ProxyAccessKey newKey = ProxyAccessKey.createKey(user, cachedState.expiry, cachedState.serverType, cachedState.scopes, charName);
      newKey.setAccessToken(accessToken.getAccessToken());
      newKey.setAccessTokenExpiry(OrbitalProperties.getCurrentTime() + TimeUnit.MILLISECONDS.convert(accessToken.getExpiresIn(), TimeUnit.SECONDS));
      newKey.setRefreshToken(accessToken.getRefreshToken());
      ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<ProxyAccessKey>() {
        @Override
        public ProxyAccessKey run() throws Exception {
          return ProxyUserAccountProvider.getFactory().getEntityManager().merge(newKey);
        }
      });

    } catch (Exception e) {
      log.log(Level.WARNING, "Failed adding new access key with error: ", e);
      callback = null;
    }

    return callback;
  }

  @Path("/get_scopes/{server}")
  @GET
  @ApiOperation(
      value = "Get available scopes from the ESI servers.",
      notes = "Retrieve map of available scopes from the ESI servers.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "map retrieved successfully"),
          @ApiResponse(
              code = 404,
              message = "unknown server type (must be one of 'latest', 'legacy', or 'dev')",
              response = ServiceError.class),
          @ApiResponse(
              code = 500,
              message = "internal service error",
              response = ServiceError.class),
      })
  public Response getScopes(
                            @Context HttpServletRequest request,
                            @PathParam("server") @ApiParam(
                                name = "server",
                                required = true,
                                value = "Server type (must be one of 'latest', 'legacy', or 'dev'") String server) {
    // Verify server type
    switch (server) {
    case "legacy":
    case "latest":
    case "dev":
      break;
    default:
      ServiceError errMsg = new ServiceError(Status.NOT_FOUND.getStatusCode(), "unknown server type (must be one of 'latest', 'legacy', or 'dev')");
      return Response.status(Status.NOT_FOUND).entity(errMsg).build();
    }
    // Retrieve swagger.json from server
    JsonObject data = null;
    try {
      URL target = new URL("https://esi.tech.ccp.is/" + server + "/swagger.json");
      HttpURLConnection conn;
      conn = (HttpURLConnection) target.openConnection();
      conn.setUseCaches(true);
      javax.json.JsonReader reader = javax.json.Json.createReader(new InputStreamReader(target.openStream()));
      data = reader.readObject();
      reader.close();
    } catch (IOException e) {
      log.log(Level.WARNING, "failed to retrieve and parse swagger.json", e);
      ServiceError errMsg = new ServiceError(Status.INTERNAL_SERVER_ERROR.getStatusCode(), "failed to retrieve and parse swagger.json");
      return Response.status(Status.INTERNAL_SERVER_ERROR).entity(errMsg).build();
    }
    // Extract security object and scopes
    Map<String, String> scopeMap = new HashMap<String, String>();
    JsonObject rawMap = data.getJsonObject("securityDefinitions").getJsonObject("evesso").getJsonObject("scopes");
    for (Entry<String, JsonValue> next : rawMap.entrySet()) {
      scopeMap.put(next.getKey(), next.getValue().toString());
    }
    // Return result
    return Response.ok().entity(scopeMap).build();
  }

}
