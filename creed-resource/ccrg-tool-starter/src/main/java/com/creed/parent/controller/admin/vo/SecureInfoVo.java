package com.creed.parent.controller.admin.vo;

import lombok.Data;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/10/25
 */
@Data
public class SecureInfoVo {
    private String symmetricKey;
    private String encryptedData;
}
