package com.creed.parent.pushnotification;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
@Slf4j
public class MSAESUtility {

  private static final Logger logger = LoggerFactory.getLogger(MSAESUtility.class);


  // @Autowired
  // private MSSSLParamsConfig sslConfig;

  public MSAESUtility() {
    super();
  }

  /**
   * AES 256 encryption using AES/GCM/NoPadding algorithm
   *
   * @param plainText
   * @return String
   */
  public String encyrpt(String randomKey, String plainText,String salt, String iv) {
    if (StringUtils.isBlank(plainText)) {
      return "";
    }

    try {

      logger.info("AES 256 encryption started");
      // AES initialization
      Cipher cipher = Cipher.getInstance(Constants.ENCRYPT_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, getSecretKeySpec(randomKey, salt), getInitialVectorSpec(iv));
      logger.info("Cipher initiated");
      // AES encryption
      byte[] encryptedText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
      logger.info("AES 256 encryption done");
      return Base64.getEncoder().encodeToString(encryptedText);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
    return null;
  }

  /**
   * AES 256 decryption using AES/GCM/NoPadding algorithm
   *
   * @param encryptText
   * @return String
   */
  // public String decrypt(String randomKey, String encryptText) {
  //
  //   try {
  //     logger.debug("AES 256 decryption started");
  //     byte[] encryptTextBytes = new org.apache.commons.codec.binary.Base64().decode(encryptText);
  //     logger.debug("decrypted text decoded");
  //     // decrypt the message
  //     byte[] decyrptTextBytes = getDecyrptTextBytes(randomKey, encryptTextBytes);
  //     String text = new String(decyrptTextBytes);
  //     logger.info("AES 256 decryption done");
  //     return text;
  //   } catch (Exception e) {
  //     logger.error(e.getMessage(), e);
  //   }
  //   return null;
  // }

  /**
   * AES 256 decryption using AES/GCM/NoPadding algorithm
   *
   * @param encryptText
   * @return String
   */
  public String decrypt(String randomKey, String encryptText,String salt, String iv) {
    if (StringUtils.isBlank(encryptText)) {
      return "";
    }
    try {
      logger.debug("AES 256 decryption started");
      byte[] encryptTextBytes = Base64.getDecoder().decode(encryptText);
      logger.debug("decrypted text decoded");
      // decrypt the message
      byte[] decyrptTextBytes = getDecyrptTextBytes(randomKey, encryptTextBytes,salt, iv);
      String text = new String(decyrptTextBytes);
      logger.info("AES 256 decryption done");
      return text;
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
    return null;
  }

  private byte[] getDecyrptTextBytes(String randomKey, byte[] encryptTextBytes, String salt, String iv) {
    byte[] decrypted = null;
    try {
      Cipher cipher = Cipher.getInstance(Constants.ENCRYPT_ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, getSecretKeySpec(randomKey, salt), getInitialVectorSpec(iv));
      logger.debug("Cipher initiated");
      decrypted = cipher.doFinal(encryptTextBytes);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e) {
      logger.error(e.getMessage(), e);
    }
    return decrypted;
  }

  // private byte[] getDecyrptTextBytes(String randomKey, byte[] encryptTextBytes) {
  //   byte[] decrypted = null;
  //   try {
  //     Cipher cipher = Cipher.getInstance(Constants.ENCRYPT_ALGORITHM);
  //     cipher.init(Cipher.DECRYPT_MODE, getSecretKeySpec(randomKey), getInitialVectorSpec());
  //     logger.debug("Cipher initiated");
  //     decrypted = cipher.doFinal(encryptTextBytes);
  //   } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e) {
  //     logger.error(e.getMessage(), e);
  //   }
  //   return decrypted;
  // }

  /**
   * To generate Initial Vector Parameter Specification
   *
   * @return GCMParameterSpec
   */
  private GCMParameterSpec getInitialVectorSpec(String iv) {
    try {
      String initialVector = new String(Base64.getDecoder().decode(iv.getBytes())); //NOSONAR
      return new GCMParameterSpec(128, initialVector.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
    return null;
  }

  /**
   * To generate Initial Vector Parameter Specification
   *
   * @return GCMParameterSpec
   */
  // private GCMParameterSpec getInitialVectorSpec() {
  //   try {
  //     String initialVector = new String(org.apache.commons.codec.binary.Base64.decodeBase64(sslConfig.getInitialVector().getBytes())); //NOSONAR
  //     return new GCMParameterSpec(128, initialVector.getBytes(StandardCharsets.UTF_8));
  //   } catch (Exception e) {
  //     logger.error(e.getMessage(), e);
  //   }
  //   return null;
  // }

  /**
   * To generate Secret Key Specification using SecretKeyFactory, secretKey, salt
   * and key size
   *
   * @return SecretKeySpec
   */
  private SecretKeySpec getSecretKeySpec(String randomKey, String saltStr) {
    try {
      SecretKeyFactory skf = SecretKeyFactory.getInstance(Constants.SECRET_KEY_FACTORY_ALGORITHM);
      String salt = new String(Base64.getDecoder().decode(saltStr.getBytes())); //NOSONAR
      PBEKeySpec spec = new PBEKeySpec(generateSecretKey(randomKey).toCharArray(), salt.getBytes(StandardCharsets.UTF_8), Constants.ITERATIONS,
        Constants.KEY_SIZE);
      SecretKey secretKey = skf.generateSecret(spec);
      return new SecretKeySpec(secretKey.getEncoded(), Constants.KEY_ALGORITHM);
    } catch (NoSuchAlgorithmException noSuchAlgo) {
      logger.info(" No Such Algorithm exists " + noSuchAlgo);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
    return null;
  }

  // private SecretKeySpec getSecretKeySpec(String randomKey) {
  //   try {
  //     SecretKeyFactory skf = SecretKeyFactory.getInstance(Constants.SECRET_KEY_FACTORY_ALGORITHM);
  //     String salt = new String(org.apache.commons.codec.binary.Base64.decodeBase64(sslConfig.getSalt().getBytes())); //NOSONAR
  //     PBEKeySpec spec = new PBEKeySpec(generateSecretKey(randomKey).toCharArray(), salt.getBytes(StandardCharsets.UTF_8), Constants.ITERATIONS,
  //             Constants.KEY_SIZE);
  //     SecretKey secretKey = skf.generateSecret(spec);
  //     return new SecretKeySpec(secretKey.getEncoded(), Constants.KEY_ALGORITHM);
  //   } catch (NoSuchAlgorithmException noSuchAlgo) {
  //     logger.info(" No Such Algorithm exists " + noSuchAlgo);
  //   } catch (Exception e) {
  //     logger.error(e.getMessage(), e);
  //   }
  //   return null;
  // }

  /**
   * To generate secret key from random key, IP address and host name of the
   * server
   *
   * @return String
   */
  private String generateSecretKey(String randomKey) {
    String sSecretkey = "";
    try {
      sSecretkey = randomKey + hostAddress + hostName;
      // sSecretkey = randomKey + nAddr.getHostAddress() + nAddr.getHostName();
      logger.info("HostAddress: {}, HostName: {}", hostAddress, hostName);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
    return sSecretkey;
  }

  private String hostAddress;
  private String hostName;

  public String getHostAddress() {
    return hostAddress;
  }

  public void setHostAddress(String hostAddress) {
    this.hostAddress = hostAddress;
  }

  public String getHostName() {
    return hostName;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }
}
