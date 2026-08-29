package com.smarttrip.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private Integer code;      // 状态码：200-成功，400-客户端错误，401-未登录，500-系统异常
    private String message;    // 提示信息
    private T data;            // 返回的数据载荷
    private Long timestamp;    // 接口响应时间戳

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}