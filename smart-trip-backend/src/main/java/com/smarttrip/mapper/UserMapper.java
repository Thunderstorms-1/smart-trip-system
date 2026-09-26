package com.smarttrip.mapper;

import com.smarttrip.entity.User;
import org.apache.ibatis.annotations.Mapper;
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

    /**
     * 【原子预扣】：带条件扣减配额，防止并发超扣 (CAS机制)
     * @return 影响行数 (1: 扣除成功, 0: 余额不足)
     */
    @Update("UPDATE user SET remaining_quota = remaining_quota - 1 " +
            "WHERE id = #{userId} AND remaining_quota > 0 AND is_deleted = 0")
    int decrementQuotaIfAvailable(Long userId);

    /**
     * 【原子补偿】：失败时回滚配额 (+1)
     */
    @Update("UPDATE user SET remaining_quota = remaining_quota + 1 " +
            "WHERE id = #{userId} AND is_deleted = 0")
    int incrementQuota(Long userId);
}