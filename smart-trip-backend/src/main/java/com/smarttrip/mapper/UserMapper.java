package com.smarttrip.mapper;

import com.smarttrip.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND is_deleted = 0 LIMIT 1")
    User findByUsername(String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND is_deleted = 0 LIMIT 1")
    User findById(Long id);
}