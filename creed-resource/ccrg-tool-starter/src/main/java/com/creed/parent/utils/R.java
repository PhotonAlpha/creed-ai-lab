package com.creed.parent.utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.exception.ErrorCode;
import com.creed.parent.constant.GlobalErrorCodeConstants;
import lombok.Data;
import org.springframework.util.Assert;

import java.io.Serializable;
import java.util.Objects;

/**
 *
 * @param <T>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> implements Serializable {


    private Integer code;

    private T data;

    private String msg;

    public static <T> R<T> error(R<?> result) {
        return error(result.getCode(), result.getMsg());
    }

    public static <T> R<T> error(Integer code, String message) {
        Assert.isTrue(!GlobalErrorCodeConstants.SUCCESS.code().equals(code), "code 必须是错误的！");
        R<T> result = new R<>();
        result.code = code;
        result.msg = message;
        return result;
    }

    public static <T> R<T> error(ErrorCode errorCode) {
        return error(errorCode.code(), errorCode.msg());
    }

    public static <T> R<T> success(T data) {
        R<T> result = new R<>();
        result.code = GlobalErrorCodeConstants.SUCCESS.code();
        result.data = data;
        result.msg = "";
        return result;
    }

    public static boolean isSuccess(Integer code) {
        return Objects.equals(code, GlobalErrorCodeConstants.SUCCESS.code());
    }

    @JsonIgnore
    public boolean isSuccess() {
        return isSuccess(code);
    }

    @JsonIgnore
    public boolean isError() {
        return !isSuccess();
    }



/*     public static <T> R<T> error(ServiceException serviceException) {
        return error(serviceException.code(), serviceException.getMessage());
    } */

}
