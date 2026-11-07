package com.creed.parent.client.vo.list;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArtifactoryResponseVO {

    private String repo;
    private String path;
    private String created;
    private String createdBy;
    private String lastModified;
    private String modifiedBy;
    private String lastUpdated;
    private String downloadUri;
    private List<ChildrenVO> children;
    private String uri;
}
