package com.creed.parent.pushnotification;

import com.creed.parent.pushnotification.dal.DecryptionInfo;
import com.creed.parent.pushnotification.dal.DecryptionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 10/13/25
 */
@Service
@Slf4j
public class CryptoService {
    public List<Pair<String, String>> decryption(DecryptionInfo info) {
        MSAESUtility msa = new MSAESUtility();
        String randomKey = info.randomKey();
        String salt = "dW9iZ2VibmdhZXMyNTZmaXhlZHNhbHQ=";
        String iv = "ZW5jcnlwdGlvbkludFZlYw==";
        // SIT
        String hostAddress = info.hostAddress();
        String hostName = info.hostName();
        msa.setHostAddress(hostAddress);
        msa.setHostName(hostName);
        List<DecryptionRecord> encryptText = info.encryptText();
        List<Pair<String, String>> list = encryptText.stream().map(s -> {
            String decryptVal = msa.decrypt(randomKey, s.encryptedText(), salt, iv);
            log.info(">>original:{} decrypted:{}<<", s.encryptedText(), decryptVal);
            return Pair.of(s.nickName(), decryptVal);
        }).toList();
        list.forEach(pair -> log.info("decrypted:{}", pair));
        return list;
    }

    public List<Pair<String, String>> encryption(DecryptionInfo info, List<Pair<String, String>> list) {
        List<Pair<String, String>> result = new ArrayList<>();
        MSAESUtility msa = new MSAESUtility();
        String randomKey = info.randomKey();
        String salt = "dW9iZ2VibmdhZXMyNTZmaXhlZHNhbHQ=";
        String iv = "ZW5jcnlwdGlvbkludFZlYw==";
        // SIT
        String hostAddress = info.hostAddress();
        String hostName = info.hostName();
        msa.setHostAddress(hostAddress);
        msa.setHostName(hostName);
        for (Pair<String, String> s : list) {
            String encyrpted = msa.encyrpt(randomKey, s.getSecond(), salt, iv);
            log.info("{}::@@encrypted::{}", s.getFirst(), encyrpted);
            result.add(Pair.of(s.getFirst(), encyrpted));
        }
        return result;
    }
}
