package com.creed.parent.convert;

import com.creed.parent.client.vo.confluence.attachment.AttachmentContentVO;
import com.creed.parent.client.vo.confluence.attachment.ResultsVO;
import com.creed.parent.client.vo.confluence.attachment.SimpleAttachmentContentVO;
import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.LinksVO;
import com.creed.parent.client.vo.confluence.metadata.AttachmentPropertyVO;
import com.creed.parent.client.vo.confluence.metadata.ContentPropertyVO;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.Collections;
import java.util.Optional;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-11-17T17
 */
@Mapper
public interface ConfluenceConvert {
    ConfluenceConvert INSTANCE = Mappers.getMapper(ConfluenceConvert.class);

    @BeanMapping(ignoreByDefault = true, qualifiedByName = "afterBuildContent")
    @Mapping(constant = "page", target = "type")
    @Mapping(source = "title", target = "title")
    @Mapping(source = "space", target = "space.key")
    @Mapping(constant = "storage", target = "body.storage.representation")
    @Mapping(source = "body", target = "body.storage.value")
    ConfluenceContentVO buildContent(String space, String ancestorId, String title, String body);


    @BeanMapping(ignoreByDefault = true, qualifiedByName = "afterBuildContent")
    @Mapping(constant = "page", target = "type")
    @Mapping(source = "title", target = "title")
    @Mapping(constant = "storage", target = "body.storage.representation")
    @Mapping(source = "body", target = "body.storage.value")
    ConfluenceContentVO buildUpdateContent(String title, String body);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "keyword", target = "key")
    @Mapping(source = "attachmentId", target = "value.metadataId")
    @Mapping(source = "createdDate", target = "value.createdDate")
    ContentPropertyVO<AttachmentPropertyVO> buildContentProperty(String id, String keyword, String attachmentId, String createdDate);

    @Mapping(source = "ancestorId", target = "id")
    ConfluenceContentVO buildAncestorContent(String ancestorId);

    @Mapping(source = "versionNo", target = "version.number")
    ConfluenceContentVO updateVersion(@MappingTarget ConfluenceContentVO content, Integer versionNo) ;

    @AfterMapping
    @Named("afterBuildContent")
    default void afterBuildContent(String ancestorId, @MappingTarget ConfluenceContentVO content) {
        content.setAncestors(Collections.singletonList(buildAncestorContent(ancestorId)));
    }

    void update(ConfluenceContentVO originalContent, @MappingTarget ConfluenceContentVO updatedContent);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "id", target = "id")
    @Mapping(source = "title", target = "title")
    @Mapping(source = "links.download", target = "download")
    @Mapping(source = "links.webui", target = "webui")
    @Mapping(source = "links.self", target = "self")
    SimpleAttachmentContentVO convert(ResultsVO vo);

    default SimpleAttachmentContentVO mapContainers(AttachmentContentVO attachment){
        ResultsVO resultsVO = Optional.ofNullable(attachment)
                .map(AttachmentContentVO::getResults)
                .orElse(Collections.emptyList())
                .stream().findFirst()
                .orElse(null);
        String baseLink = Optional.ofNullable(attachment).map(AttachmentContentVO::getLinks)
                .map(LinksVO::getBase).orElse(StringUtils.EMPTY);
        SimpleAttachmentContentVO simpleAttachmentContent = convert(resultsVO);
        simpleAttachmentContent.setBase(baseLink);
        return simpleAttachmentContent;
    }
}
