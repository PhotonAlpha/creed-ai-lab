package com.creed.parent.utils;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/30/24
 */
@UtilityClass
public class StrUtils {

    public static final String SEPARATOR = "/";

    public String substringBeforeLast(String str) {
        return substringBeforeLast(str, SEPARATOR);
    }
    public String substringBeforeLast(String str, final String separator) {
        if (StringUtils.contains(str, separator)) {
            return StringUtils.substringBeforeLast(str, separator);
        } else {
            return StringUtils.EMPTY;
        }
    }
    public String substringAfterLast(String str, final String separator) {
        if (StringUtils.contains(str, separator)) {
            return StringUtils.substringAfterLast(str, separator);
        } else {
            return str;
        }
    }
    public String substringAfterLast(String str) {
        return substringAfterLast(str, SEPARATOR);
    }
}
