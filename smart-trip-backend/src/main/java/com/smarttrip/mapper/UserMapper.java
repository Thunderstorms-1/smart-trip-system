package com.smarttrip.mapper;

import com.smarttrip.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND is_deleted = 0 LIMIT 1")
    User findByUsername(String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND is_deleted = 0 LIMIT 1")
    User findById(Long id);

    @Select("SELECT id, username, password, nickname, avatar, remaining_quota, is_deleted, create_time " +
            "FROM user WHERE id = #{id} AND is_deleted = 0")
    User selectById(Long id);

    // =========================================================================
    // 【风控核心 1】：CAS 条件预扣配额（防并发超扣）
    // 只有当已用次数小于每日总配额时，才允许 +1
    // =========================================================================
    @Update("UPDATE sys_user SET ai_quota_used = ai_quota_used + 1, update_time = NOW() " +
            "WHERE id = #{userId} AND ai_quota_used < ai_quota_daily AND is_deleted = 0")
    int tryPreDeductUserQuota(@Param("userId") Long userId);

    // =========================================================================
    // 【风控核心 2】：幂等安全补偿回滚（失败时恢复配额）
    // 只有在 ai_quota_used > 0 时才允许 -1，防止误回滚导致负数
    // =========================================================================
    @Update("UPDATE sys_user SET ai_quota_used = ai_quota_used - 1, update_time = NOW() " +
            "WHERE id = #{userId} AND ai_quota_used > 0 AND is_deleted = 0")
    int compensateRollbackUserQuota(@Param("userId") Long userId);
}