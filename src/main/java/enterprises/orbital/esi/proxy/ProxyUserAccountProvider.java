package enterprises.orbital.esi.proxy;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.db.ConnectionFactory;
import enterprises.orbital.oauth.UserAccount;
import enterprises.orbital.oauth.UserAccountProvider;
import enterprises.orbital.oauth.UserAuthSource;

public class ProxyUserAccountProvider implements UserAccountProvider {
  public static final String USER_ACCOUNT_PU_PROP    = "enterprises.orbital.esi.proxy.persistence_unit";
  public static final String USER_ACCOUNT_PU_DEFAULT = "esiproxy-production";

  public static ConnectionFactory getFactory() {
    return ConnectionFactory.getFactory(OrbitalProperties.getGlobalProperty(USER_ACCOUNT_PU_PROP, USER_ACCOUNT_PU_DEFAULT));
  }

  @Override
  public UserAccount getAccount(
                                String uid) {
    long user_id = 0;
    try {
      user_id = Long.valueOf(uid);
    } catch (NumberFormatException e) {
      user_id = 0;
    }
    return ProxyUserAccount.getAccount(user_id);
  }

  @Override
  public UserAuthSource getSource(
                                  UserAccount acct,
                                  String source) {
    assert acct instanceof ProxyUserAccount;
    return ProxyUserAuthSource.getSource((ProxyUserAccount) acct, source);
  }

  @Override
  public void removeSourceIfExists(
                                   UserAccount acct,
                                   String source) {
    assert acct instanceof ProxyUserAccount;
    ProxyUserAuthSource.removeSourceIfExists((ProxyUserAccount) acct, source);
  }

  @Override
  public UserAuthSource getBySourceScreenname(
                                              String source,
                                              String screenName) {
    return ProxyUserAuthSource.getBySourceScreenname(source, screenName);
  }

  @Override
  public UserAuthSource createSource(
                                     UserAccount newUser,
                                     String source,
                                     String screenName,
                                     String body) {
    assert newUser instanceof ProxyUserAccount;
    return ProxyUserAuthSource.createSource((ProxyUserAccount) newUser, source, screenName, body);
  }

  @Override
  public UserAccount createNewUserAccount(
                                          boolean disabled) {
    return ProxyUserAccount.createNewUserAccount(false, !disabled);
  }

}
