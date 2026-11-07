package com.creed.parent.exception;

import com.creed.parent.utils.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.Set;

import static com.creed.parent.constant.GlobalErrorCodeConstants.BAD_REQUEST;
import static com.creed.parent.constant.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static com.creed.parent.constant.GlobalErrorCodeConstants.METHOD_NOT_ALLOWED;
import static com.creed.parent.constant.GlobalErrorCodeConstants.NOT_FOUND;


@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    public static final Set<String> IGNORE_ERROR_MESSAGES = Collections.emptySet();

    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public R<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException ex) {
        log.warn("[missingServletRequestParameterExceptionHandler]", ex);
        return R.error(BAD_REQUEST.code(), String.format("Request parameter missing:%s", ex.getParameterName()));
    }


    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<?> methodArgumentTypeMismatchExceptionHandler(MethodArgumentTypeMismatchException ex) {
        log.warn("[missingServletRequestParameterExceptionHandler]", ex);
        return R.error(BAD_REQUEST.code(), String.format("Request parameter type error:%s", ex.getMessage()));
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<?> methodArgumentNotValidExceptionExceptionHandler(MethodArgumentNotValidException ex) {
        log.warn("[methodArgumentNotValidExceptionExceptionHandler]", ex);
        FieldError fieldError = ex.getBindingResult().getFieldError();
        assert fieldError != null; // 断言，避免告警
        return R.error(BAD_REQUEST.code(), String.format("The request parameters are incorrect.:%s", fieldError.getDefaultMessage()));
    }


    @ExceptionHandler(BindException.class)
    public R<?> bindExceptionHandler(BindException ex) {
        log.warn("[handleBindException]", ex);
        FieldError fieldError = ex.getFieldError();
        assert fieldError != null;
        return R.error(BAD_REQUEST.code(), String.format("The request parameters are incorrect:%s", fieldError.getDefaultMessage()));
    }

    @ExceptionHandler(value = ConstraintViolationException.class)
    public R<?> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        log.warn("[constraintViolationExceptionHandler]", ex);
        ConstraintViolation<?> constraintViolation = ex.getConstraintViolations().iterator().next();
        return R.error(BAD_REQUEST.code(), String.format("The request parameters are incorrect:%s", constraintViolation.getMessage()));
    }

    @ExceptionHandler(value = ValidationException.class)
    public R<?> validationException(ValidationException ex) {
        log.warn("[constraintViolationExceptionHandler]", ex);
        return R.error(BAD_REQUEST);
    }


    @ExceptionHandler(NoHandlerFoundException.class)
    public R<?> noHandlerFoundExceptionHandler(HttpServletRequest req, NoHandlerFoundException ex) {
        log.warn("[noHandlerFoundExceptionHandler]", ex);
        return R.error(NOT_FOUND.code(), String.format("The requested address does not exist:%s", ex.getRequestURL()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    private R<?> noResourceFoundExceptionHandler(HttpServletRequest req, NoResourceFoundException ex) {
        log.warn("[noResourceFoundExceptionHandler]", ex);
        return R.error(NOT_FOUND.code(), String.format("The requested address does not exist:%s", ex.getResourcePath()));
    }


    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<?> httpRequestMethodNotSupportedExceptionHandler(HttpRequestMethodNotSupportedException ex) {
        log.warn("[httpRequestMethodNotSupportedExceptionHandler]", ex);
        return R.error(METHOD_NOT_ALLOWED.code(), String.format("Incorrect request method:%s", ex.getMessage()));
    }


    @ExceptionHandler(value = ServiceException.class)
    public R<?> serviceExceptionHandler(ServiceException ex) {
        log.error("ex", ex);
        if (!IGNORE_ERROR_MESSAGES.contains(ex.getMessage())) {
            StackTraceElement[] stackTrace = ex.getStackTrace();
            log.warn("[serviceExceptionHandler]\n\t{}", stackTrace[0]);
        }
        return R.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(value = Exception.class)
    public R<?> defaultExceptionHandler(HttpServletRequest req, Throwable ex) {
        log.error("[defaultExceptionHandler]", ex);
        return R.error(INTERNAL_SERVER_ERROR.code(), INTERNAL_SERVER_ERROR.msg());
    }

}
