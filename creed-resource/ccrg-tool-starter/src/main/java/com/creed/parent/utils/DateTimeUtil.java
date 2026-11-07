package com.creed.parent.utils;

import lombok.experimental.UtilityClass;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/30/24
 */
@UtilityClass
public class DateTimeUtil {
    public static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("uuuu-MM-dd-HHmmssS");
    public static String getTimeStr() {
        return LocalDateTime.now().format(DTF);
    }
}
