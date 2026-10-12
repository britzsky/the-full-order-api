package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MenuAliasMapper {
	List<Map<String, Object>> aliases(Map<String, Object> p);

	String activeMenuName(Map<String, Object> p);

	int upsertAlias(Map<String, Object> p);

	int deleteAlias(Map<String, Object> p);
}
