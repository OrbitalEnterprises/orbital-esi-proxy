package enterprises.orbital.esi.proxy;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.core.Application;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.base.PersistentProperty;
import enterprises.orbital.db.DBPropertyProvider;
import enterprises.orbital.oauth.AuthUtil;

public class ProxyApplication extends Application {
  // Property which holds the name of the persistence unit
  public static final String PROP_PROPERTIES_PU = "enterprises.orbital.esi.proxy.persistence_unit";

  public ProxyApplication() throws IOException {
    // Populate properties
    OrbitalProperties.addPropertyFile("ESIProxy.properties");
    // Sent persistence unit
    PersistentProperty.setProvider(new DBPropertyProvider(OrbitalProperties.getGlobalProperty(PROP_PROPERTIES_PU)));
    // Set UserAccountProvider provider
    AuthUtil.setUserAccountProvider(new ProxyUserAccountProvider());
  }

  @Override
  public Set<Class<?>> getClasses() {
    Set<Class<?>> resources = new HashSet<Class<?>>();
    // Local resources
    resources.add(ServicesWS.class);
    // Swagger additions
    resources.add(io.swagger.jaxrs.listing.ApiListingResource.class);
    resources.add(io.swagger.jaxrs.listing.SwaggerSerializers.class);
    // Return resource set
    return resources;
  }

}
