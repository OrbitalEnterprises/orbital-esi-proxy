package enterprises.orbital.esi.proxy;

/**
 * Indicates given key ID could not be found.
 */
public class NoSuchKeyException extends RuntimeException {

  private static final long serialVersionUID = -7195669711115629774L;

  public NoSuchKeyException() {
    super();
  }

  public NoSuchKeyException(String arg0) {
    super(arg0);
  }

  public NoSuchKeyException(Throwable arg0) {
    super(arg0);
  }

  public NoSuchKeyException(String arg0, Throwable arg1) {
    super(arg0, arg1);
  }

}
