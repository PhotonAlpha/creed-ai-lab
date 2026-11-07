package com.creed.parent.context;

import com.alibaba.ttl.TransmittableThreadLocal;

public class TenantContextHolder {

    private static final ThreadLocal<Boolean> UPLOAD_CONFLUENCE = new TransmittableThreadLocal<>();

    public static void setUploadConfluence(Boolean ignore) {
        UPLOAD_CONFLUENCE.set(ignore);
    }

    public static boolean isUploadConfluence() {
        return Boolean.TRUE.equals(UPLOAD_CONFLUENCE.get());
    }

    public static void clear() {
        UPLOAD_CONFLUENCE.remove();
    }

}
