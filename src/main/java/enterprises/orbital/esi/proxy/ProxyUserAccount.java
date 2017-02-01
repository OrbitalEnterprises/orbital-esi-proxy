package enterprises.orbital.esi.proxy;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.NoResultException;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.TypedQuery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.base.PersistentPropertyKey;
import enterprises.orbital.db.ConnectionFactory.RunInTransaction;
import enterprises.orbital.oauth.UserAccount;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Proxy user account entries
 */
@Entity
@Table(
    name = "proxy_users")
@NamedQueries({
    @NamedQuery(
        name = "ProxyUserAccount.findByUid",
        query = "SELECT c FROM ProxyUserAccount c where c.uid = :uid"),
    @NamedQuery(
        name = "ProxyUserAccount.allAccounts",
        query = "SELECT c FROM ProxyUserAccount c"),
})
@ApiModel(
    description = "Proxy user account")
@JsonSerialize(
    typing = JsonSerialize.Typing.DYNAMIC)
public class ProxyUserAccount implements UserAccount, PersistentPropertyKey<String> {
  private static final Logger log     = Logger.getLogger(ProxyUserAccount.class.getName());

  @Id
  @GeneratedValue(
      strategy = GenerationType.SEQUENCE,
      generator = "proxy_seq")
  @SequenceGenerator(
      name = "proxy_seq",
      initialValue = 100000,
      allocationSize = 10,
      sequenceName = "account_sequence")
  @ApiModelProperty(
      value = "Unique user ID")
  @JsonProperty("uid")
  protected long              uid;
  @ApiModelProperty(
      value = "True if user is active, false otherwise")
  @JsonProperty("active")
  protected boolean           active;
  @ApiModelProperty(
      value = "Date (milliseconds UTC) when account was created")
  @JsonProperty("created")
  protected long              created = -1;
  @ApiModelProperty(
      value = "True if user is an admin, false otherwise")
  @JsonProperty("admin")
  protected boolean           admin;
  @ApiModelProperty(
      value = "Last time (milliseconds UTC) user logged in")
  @JsonProperty("last")
  protected long              last    = -1;

  public long getID() {
    return uid;
  }

  @Override
  public String getUid() {
    return String.valueOf(uid);
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(
                        boolean active) {
    this.active = active;
  }

  public long getCreated() {
    return created;
  }

  public void setCreated(
                         long created) {
    this.created = created;
  }

  public boolean isAdmin() {
    return admin;
  }

  public void setAdmin(
                       boolean admin) {
    this.admin = admin;
  }

  public long getLast() {
    return last;
  }

  public void setLast(
                      long last) {
    this.last = last;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (active ? 1231 : 1237);
    result = prime * result + (admin ? 1231 : 1237);
    result = prime * result + (int) (created ^ (created >>> 32));
    result = prime * result + (int) (last ^ (last >>> 32));
    result = prime * result + (int) (uid ^ (uid >>> 32));
    return result;
  }

  @Override
  public boolean equals(
                        Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    ProxyUserAccount other = (ProxyUserAccount) obj;
    if (active != other.active) return false;
    if (admin != other.admin) return false;
    if (created != other.created) return false;
    if (last != other.last) return false;
    if (uid != other.uid) return false;
    return true;
  }

  @Override
  public String toString() {
    return "ProxyUserAccount [uid=" + uid + ", active=" + active + ", created=" + created + ", admin=" + admin + ", last=" + last + "]";
  }

  public ProxyUserAccount copy() {
    ProxyUserAccount copy = new ProxyUserAccount();
    copy.uid = uid;
    copy.active = active;
    copy.created = created;
    copy.admin = admin;
    copy.last = last;
    return copy;
  }

  /**
   * Create a new user account.
   * 
   * @param admin
   *          true if this user should be created with administrative privileges.
   * @param active
   *          true if this user should be initially active.
   * @return the new ProxyUserAccount.
   */
  public static ProxyUserAccount createNewUserAccount(
                                                      final boolean admin,
                                                      final boolean active) {
    try {
      return ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<ProxyUserAccount>() {
        @Override
        public ProxyUserAccount run() throws Exception {
          ProxyUserAccount result = new ProxyUserAccount();
          result.active = active;
          result.created = OrbitalProperties.getCurrentTime();
          result.admin = admin;
          result.last = result.created;
          return ProxyUserAccountProvider.getFactory().getEntityManager().merge(result);
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "query error", e);
    }
    return null;
  }

  /**
   * Retrieve the user account with the given id.
   * 
   * @param uid
   *          the ID of the user account to retrieve.
   * @return the given UserAccount, or null if no such user exists.
   */
  public static ProxyUserAccount getAccount(
                                            final long uid) {
    try {
      return ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<ProxyUserAccount>() {
        @Override
        public ProxyUserAccount run() throws Exception {
          TypedQuery<ProxyUserAccount> getter = ProxyUserAccountProvider.getFactory().getEntityManager().createNamedQuery("ProxyUserAccount.findByUid",
                                                                                                                          ProxyUserAccount.class);
          getter.setParameter("uid", uid);
          try {
            return getter.getSingleResult();
          } catch (NoResultException e) {
            return null;
          }
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "query error", e);
    }
    return null;
  }

  /**
   * Update the "last" time for this user to the current time.
   * 
   * @param user
   *          the UserAccount to update.
   * @return returns the newly persisted User.
   */
  public static ProxyUserAccount touch(
                                       final ProxyUserAccount user) {
    try {
      return ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<ProxyUserAccount>() {
        @Override
        public ProxyUserAccount run() throws Exception {
          ProxyUserAccount result = getAccount(user.uid);
          if (result == null) throw new IOException("No user found with UUID " + user.getUid());
          result.last = OrbitalProperties.getCurrentTime();
          return ProxyUserAccountProvider.getFactory().getEntityManager().merge(result);
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "query error", e);
    }
    return null;
  }

  /**
   * Return list of all user accounts.
   * 
   * @return the list of all current user accounts.
   */
  public static List<ProxyUserAccount> getAllAccounts() {
    try {
      return ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<List<ProxyUserAccount>>() {
        @Override
        public List<ProxyUserAccount> run() throws Exception {
          TypedQuery<ProxyUserAccount> getter = ProxyUserAccountProvider.getFactory().getEntityManager().createNamedQuery("ProxyUserAccount.allAccounts",
                                                                                                                          ProxyUserAccount.class);
          return getter.getResultList();
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "query error", e);
    }
    return null;
  }

  @Override
  public boolean isDisabled() {
    return !active;
  }

  @Override
  public void touch() {
    touch(this);
  }

  @Override
  public Date getJoinTime() {
    return new Date(created);
  }

  @Override
  public Date getLastSignOn() {
    return new Date(last);
  }

  @Override
  public String getPeristentPropertyKey(
                                        String field) {
    // Key scheme: ProxyUserAccount.<UUID>.<field>
    return "ProxyUserAccount." + String.valueOf(uid) + "." + field;
  }

  public static ProxyUserAccount update(
                                        final ProxyUserAccount data) {
    try {
      return ProxyUserAccountProvider.getFactory().runTransaction(new RunInTransaction<ProxyUserAccount>() {
        @Override
        public ProxyUserAccount run() throws Exception {
          return ProxyUserAccountProvider.getFactory().getEntityManager().merge(data);
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "query error", e);
    }
    return null;
  }

}
