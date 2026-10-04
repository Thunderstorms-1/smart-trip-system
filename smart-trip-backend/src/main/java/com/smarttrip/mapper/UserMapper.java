package com.smarttrip.mapper;

import com.smarttrip.entity.User;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Update(
      "UPDATE sys_user SET"
          + " nickname=#{nickname},bio=#{bio},home_city=#{homeCity},travel_preferences=#{travelPreferences},update_time=NOW()"
          + " WHERE id=#{id} AND is_deleted=0")
  int updateProfile(User user);

  @Update("UPDATE sys_user SET avatar=#{avatar},update_time=NOW() WHERE id=#{id} AND is_deleted=0")
  int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

  @Update(
      "UPDATE sys_user SET password=#{password},token_version=token_version+1,update_time=NOW()"
          + " WHERE id=#{id} AND password=#{previous} AND is_deleted=0")
  int updatePassword(
      @Param("id") Long id, @Param("password") String password, @Param("previous") String previous);

  @Update("UPDATE sys_user SET token_version=token_version+1 WHERE id=#{id}")
  int invalidateTokens(Long id);

  @Select("SELECT * FROM sys_user WHERE username = #{username} AND is_deleted = 0 LIMIT 1")
  User findByUsername(@Param("username") String username);

  @Select("SELECT * FROM sys_user WHERE id = #{id} AND is_deleted = 0 LIMIT 1")
  User findById(@Param("id") Long id);

  // =========================================================================
  // 【新增】：用户注册持久化落库（默认每日 10 次 AI 配额，自增主键回填）
  // =========================================================================
  @Insert(
      "INSERT INTO sys_user ("
          + "  username, password, nickname, ai_quota_daily, ai_quota_used, status, is_deleted"
          + ") VALUES ("
          + "  #{username}, #{password}, #{nickname}, 10, 0, 1, 0"
          + ")")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertUser(User user);

  // CAS 条件预扣配额
  @Update(
      "UPDATE sys_user SET ai_quota_used = ai_quota_used + 1, update_time = NOW() "
          + "WHERE id = #{userId} AND ai_quota_used < ai_quota_daily AND is_deleted = 0")
  int tryPreDeductUserQuota(@Param("userId") Long userId);

  // 幂等配额回滚补偿
  @Update(
      "UPDATE sys_user SET ai_quota_used = ai_quota_used - 1, update_time = NOW() "
          + "WHERE id = #{userId} AND ai_quota_used > 0 AND is_deleted = 0")
  int compensateRollbackUserQuota(@Param("userId") Long userId);
}
