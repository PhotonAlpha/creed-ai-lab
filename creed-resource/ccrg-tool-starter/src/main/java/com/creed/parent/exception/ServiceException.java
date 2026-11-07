package com.creed.parent.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public final class ServiceException extends RuntimeException {


    private Integer code;
    private String message;

    public ServiceException() {
    }

    public ServiceException(ErrorCode errorCode) {
        this.code = errorCode.code();
        this.message = errorCode.msg();
    }

    public ServiceException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public ServiceException setCode(Integer code) {
        this.code = code;
        return this;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public ServiceException setMessage(String message) {
        this.message = message;
        return this;
    }

}
