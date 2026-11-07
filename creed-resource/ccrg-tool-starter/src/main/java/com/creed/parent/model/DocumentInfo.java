package com.creed.parent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 29/5/25
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DocumentInfo {
    private String path;
    private String extension;
    private String folderName;
    private String content;
}
