package com.upc.computer.face;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Follows the existing Result envelope while never reflecting image/password/upstream payloads. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FaceAuthController.class)
public class FaceExceptionAdvice {
    @ExceptionHandler(FaceException.class)
    public Result<Void> face(FaceException error) { return Result.fail(error.status(), error.getMessage()); }
    @ExceptionHandler(BusinessException.class)
    public Result<Void> business(BusinessException error) { return Result.fail(error.getCode(), "账号状态已变化，请使用密码登录或联系管理员"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> malformed() { return Result.fail(400, "请求格式无效"); }
    @ExceptionHandler(Exception.class)
    public Result<Void> unavailable() { return Result.fail(503, "人脸操作暂不可用，请检查数据库迁移、Redis 和服务配置；可继续使用密码登录"); }
}
