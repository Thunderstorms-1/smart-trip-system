package com.smarttrip.common;

public class UserContext {
    private static final ThreadLocal<Long> USER_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前登录用户的 ID
     */
    public static void setUserId(Long userId) {
        USER_HOLDER.set(userId);
    }

    /**
     * 获取当前登录用户的 ID
     */
    public static Long getUserId() {
        return USER_HOLDER.get();
    }

    /**
     * 清理 ThreadLocal（必须！防止 Tomcat 线程池复用导致内存泄漏和数据串号）
     */
    public static void clear() {
        USER_HOLDER.remove();
    }
}