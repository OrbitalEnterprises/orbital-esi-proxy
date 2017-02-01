package enterprises.orbital.esi.proxy;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(
    description = "Proxy Web Service Error")
public class ServiceError {
  private int    errorCode    = 0;
  private String errorMessage = "";

  public ServiceError(int errorCode, String errorMessage) {
    super();
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }

  @ApiModelProperty(
      value = "Error code")
  @JsonProperty("errorCode")
  public int getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(
                           int errorCode) {
    this.errorCode = errorCode;
  }

  @ApiModelProperty(
      value = "Error message")
  @JsonProperty("errorMessage")
  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(
                              String errorMessage) {
    this.errorMessage = errorMessage;
  }

}
