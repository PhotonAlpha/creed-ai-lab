package com.creed.parent.constant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.creed.parent.convert.AbstractEnumConverter;
import com.creed.parent.convert.PersistEnum2DB;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

@Getter
@AllArgsConstructor
public enum CommonStatusEnum implements PersistEnum2DB<Integer> {

    ENABLE(0, "enabled"),
    DISABLE(1, "disabled");


    private final Integer status;
    private final String name;


    public static boolean isEnable(Integer status) {
        return Objects.equals(ENABLE.status, status);
    }

    public static boolean isDisable(Integer status) {
        return Objects.equals(DISABLE.status, status);
    }

    @Override
    @JsonValue
    public Integer getData() {
        return status;
    }

    @JsonCreator
    public static CommonStatusEnum convert(int status) {
        return Arrays.stream(values())
                .filter(e -> e.status == status)
                .findFirst()
                .orElse(DISABLE);
    }

    public static class Converter extends AbstractEnumConverter<CommonStatusEnum, Integer> {
        public Converter() {
            super(CommonStatusEnum.class);
        }
    }
}
