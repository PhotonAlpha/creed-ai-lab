package com.creed.parent.pushnotification.dal;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 10/13/25
 */
public record DecryptionInfo(String randomKey, String hostName, String hostAddress, List<DecryptionRecord> encryptText) {
}
