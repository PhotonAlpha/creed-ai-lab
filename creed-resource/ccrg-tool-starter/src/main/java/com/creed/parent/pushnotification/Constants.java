package com.creed.parent.pushnotification;

public class Constants {
  //Added private constructor to fix sonar qube issue. It was not recognised "@NoArgsConstructor(access = AccessLevel.PRIVATE)"
  private Constants() {
  }
  public static final String DEFAULT_COUNTRY = "SG";
  public static final String STRING_Y = "Y";
  public static final String CHECK_PERMISSION_TNX_STATUS = "transactionStatus";
  public static final String PROXY_TYPE = "proxy_type";
  public static final String PROXY_TYPE_WITHT = "proxy_Type";
  public static final String PAY_NOW = "PayNow";
  public static final String G3_MODE_FAST = "I";
  //crypto service constants
  public static final int ITERATIONS = 200704;
  public static final int KEY_SIZE = 256;
  public static final String KEY_ALGORITHM = "AES";
  public static final String ENCRYPT_ALGORITHM = "AES/GCM/NoPadding";
  public static final String SECRET_KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA512";
  public static final String COOKIE_GWSAID = "GWSAID";


}
