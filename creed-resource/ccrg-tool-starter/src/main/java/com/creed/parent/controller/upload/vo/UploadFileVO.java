package com.creed.parent.controller.upload.vo;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 26/5/25
 */
@Data
public class UploadFileVO {
    private String project;
    private String releasePrefix;
    private String releaseId;
    private MultipartFile file;
}
