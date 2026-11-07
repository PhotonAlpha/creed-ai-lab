package com.creed.parent.convert;

import com.creed.parent.listener.EmitterProtocol;
import com.creed.parent.listener.vo.ProgressVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T00
 */
@Mapper
public interface EmitterConvert {
    EmitterConvert INSTANCE = Mappers.getMapper(EmitterConvert.class);

    ProgressVo convert(EmitterProtocol protocol);

    @Mapping(source = "id", target = "connectionId")
    @Mapping(source = "count", target = "reportCount")
    @Mapping(source = "total", target = "total")
    ProgressVo convertMock(String id, AtomicInteger count, AtomicInteger total);
}
