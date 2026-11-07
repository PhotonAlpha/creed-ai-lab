package com.creed.parent.convert;

import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

@Slf4j
public abstract class AbstractEnumConverter<A extends Enum<A> & PersistEnum2DB<D>, D> implements AttributeConverter<A, D> {
    private final Class<A> clazz;

    protected AbstractEnumConverter(Class<A> clazz) {
        this.clazz = clazz;
    }

    @Override
    public D convertToDatabaseColumn(A attribute) {
        return Objects.nonNull(attribute) ? attribute.getData() : null;
    }

    @Override
    public A convertToEntityAttribute(D dbData) {
        if (Objects.isNull(dbData) || (dbData instanceof String str && StringUtils.isBlank(str))) {
            log.warn("trying to convertToEntityAttribute dbData is NULL");
            return null;
        }
        A[] enums = clazz.getEnumConstants();
        for (A e : enums) {
            if (e.getData().equals(dbData)) {
                return e;
            }
        }
        throw new UnsupportedOperationException(String.format("Convert to Enum Error. Enum:%s,DB value:%s", clazz.getSimpleName(), dbData));
    }
}
