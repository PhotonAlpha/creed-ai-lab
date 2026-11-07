package com.creed.parent.controller.admin;

import com.creed.parent.controller.admin.vo.SecureInfoVo;
import com.creed.parent.utils.R;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/10/25
 */
@RestController
@RequestMapping("/api/secure")
public class DecryptionController {
    @PostMapping("/decrypt")
    public R<String> installFiles(@RequestBody SecureInfoVo secureInfoVo) throws Exception {
        // String encryptedSymmetricKey = secureInfoVo.getEncryptedSymmetricKey();
        // byte[] data = Base64.getDecoder().decode(encryptedSymmetricKey);
        // String salt = secureInfoVo.getSalt();//"9736840d0bb9f4a3";
        // String secKey = secureInfoVo.getSecKey();//"74e9cf7e98fd501fa6373438bbe6651c";
        // byte[] plainData = Encryptors.stronger(secKey, salt).decrypt(data);
        String symmetricKeyPair = secureInfoVo.getSymmetricKey();//new String(plainData, StandardCharsets.UTF_8);

        String encryptedVal = secureInfoVo.getEncryptedData();

        byte[] key = Hex.decode(symmetricKeyPair);
        byte[] binaryData = Base64.getDecoder().decode(encryptedVal);
        byte[] iv = Arrays.copyOfRange(binaryData, 0, 16);
        byte[] encryptedData = Arrays.copyOfRange(binaryData, 16, binaryData.length);

        SecretKey secretKey = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance(SymmetricFactoryAlgorithm.CTR_NOPADDING.getAlgorithm());
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        String result = new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);
        return R.success(result);
    }

    enum SymmetricFactoryAlgorithm {
        CBC_PKCS5PADDING("AES/CBC/PKCS5Padding"),
        CTR_NOPADDING("AES/CTR/NoPadding"),
        GCM_NOPADDING("AES/GCM/NoPadding"),
        ;

        private String algorithm;

        SymmetricFactoryAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getAlgorithm() {
            return algorithm;
        }
    }
}
