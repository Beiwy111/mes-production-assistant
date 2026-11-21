package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.annotations.*;

/** Deliberately outside legacy mapper scanning. Only the standalone gateway registers it. */
public interface WorkbenchIdentityReadMapper {
    @Select("SELECT r.role_code,u.customer_name FROM `user` u JOIN role r ON r.role_id=u.role_id WHERE u.user_id=#{userId} AND u.status=1 AND r.status=1")
    java.util.Map<String,Object> identity(@Param("userId") long userId);
    @Select("SELECT m.menu_code FROM `user` u JOIN role r ON r.role_id=u.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE u.user_id=#{userId} AND u.status=1 AND r.status=1 AND m.status=1")
    java.util.List<String> menuCodes(@Param("userId") long userId);
    @Select("SELECT COUNT(*) FROM `user` u JOIN role r ON r.role_id = u.role_id WHERE u.user_id = #{userId} AND u.status = 1 AND r.status = 1 AND r.role_code = 'ADMIN'")
    int activeAdmin(@Param("userId") long userId);
    @Select("SELECT COUNT(*) FROM `user` u JOIN role r ON r.role_id = u.role_id WHERE u.user_id = #{userId} AND u.status = 1 AND r.status = 1 AND r.role_code IN ('ADMIN', 'ORDER', 'CUSTOMER')")
    int activeOrderIntakeUser(@Param("userId") long userId);
}
