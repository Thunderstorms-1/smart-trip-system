package com.smarttrip.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {
    private Long total;        // 符合条件的总记录数
    private Integer pageNum;   // 当前页码
    private Integer pageSize;  // 每页条数
    private List<T> list;      // 当前页数据列表
}