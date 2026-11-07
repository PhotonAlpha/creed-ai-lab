package com.creed.parent.constant;


import com.creed.parent.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public interface GlobalErrorCodeConstants {

    ErrorCode SUCCESS = new ErrorCode(0, HttpStatus.OK.getReasonPhrase());

    ErrorCode BAD_REQUEST = new ErrorCode(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());
    ErrorCode UNAUTHORIZED = new ErrorCode(401, HttpStatus.UNAUTHORIZED.getReasonPhrase());
    ErrorCode FORBIDDEN = new ErrorCode(403, HttpStatus.FORBIDDEN.getReasonPhrase());
    ErrorCode NOT_FOUND = new ErrorCode(404, HttpStatus.NOT_FOUND.getReasonPhrase());
    ErrorCode METHOD_NOT_ALLOWED = new ErrorCode(405, HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase());
    ErrorCode LOCKED = new ErrorCode(423, HttpStatus.LOCKED.getReasonPhrase());
    ErrorCode INTERNAL_SERVER_ERROR = new ErrorCode(HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());


    ErrorCode DICT_NAME_DUPLICATE = new ErrorCode(1_002_004_000, "dict name duplicate");
    ErrorCode DICT_PARENT_NOT_EXITS = new ErrorCode(1_002_004_001,"dict parent not exits");
    ErrorCode DICT_NOT_FOUND = new ErrorCode(1_002_004_002, "dict not found");
    ErrorCode DICT_EXITS_CHILDREN = new ErrorCode(1_002_004_003, "Sub-element exists and cannot be deleted");
    ErrorCode DICT_PARENT_ERROR = new ErrorCode(1_002_004_004, "Cannot set itself as parent");
    ErrorCode DICT_NOT_ENABLE = new ErrorCode(1_002_004_006, "dict not enable");
    ErrorCode DICT_PARENT_IS_CHILD = new ErrorCode(1_002_004_007, "dict parent is child");

    ErrorCode APP_CODE_NOT_FOUND = new ErrorCode(1_003_000_001, "app code not found");

    ErrorCode OPERATION_NOT_SUPPORT = new ErrorCode(1_000_000_001, "operation not support");

}
