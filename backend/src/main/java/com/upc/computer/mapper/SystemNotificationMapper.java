package com.upc.computer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** sys_notification 通用消息读写。 */
@Mapper
public interface SystemNotificationMapper {

    @org.apache.ibatis.annotations.Update("""
        CREATE TABLE IF NOT EXISTS sys_notification (
          notification_id bigint unsigned NOT NULL AUTO_INCREMENT,
          receiver_role varchar(30) NOT NULL,
          title varchar(200) NOT NULL,
          content varchar(1000) DEFAULT NULL,
          level varchar(20) NOT NULL DEFAULT 'INFO',
          business_type varchar(40) DEFAULT NULL,
          business_id varchar(100) DEFAULT NULL,
          target_path varchar(255) DEFAULT NULL,
          read_status tinyint NOT NULL DEFAULT 0,
          created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (notification_id),
          KEY idx_notification_role (receiver_role,read_status,created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统业务通知'
        """)
    void ensureTable();

    @Insert("""
        INSERT INTO sys_notification
        (receiver_role,title,content,level,business_type,business_id,target_path,read_status,created_at)
        VALUES
        (#{receiverRole},#{title},#{content},#{level},#{businessType},#{businessId},#{targetPath},0,NOW())
        """)
    @Options(useGeneratedKeys = true, keyProperty = "notificationId")
    int insert(Map<String, Object> row);

    @Select("""
        SELECT notification_id notificationId,receiver_role receiverRole,title,content,level,
               business_type businessType,business_id businessId,target_path targetPath,
               read_status readStatus,created_at createdAt
        FROM sys_notification
        WHERE receiver_role=#{roleCode} OR receiver_role='ALL'
        ORDER BY created_at DESC,notification_id DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> listForRole(@Param("roleCode") String roleCode, @Param("limit") int limit);
}
