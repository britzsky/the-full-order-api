package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MenuMapper {
	
	String NowDateKey();
	List<Map<String, Object>> MenuList(Map<String, Object> paramMap);
	List<Map<String, Object>> DetailList(Map<String, Object> paramMap);
	List<Map<String, Object>> IngredientsList(Map<String, Object> paramMap);
	int MenuSave(Map<String, Object> paramMap);
	int IngredientsSave(Map<String, Object> paramMap);
	int AccountMenuSave(Map<String, Object> paramMap);
	int AccountIngredientsSave(Map<String, Object> paramMap);
	int AccountIngredientsMasterSave(Map<String, Object> paramMap);
	int AccountInventorySave(Map<String, Object> paramMap);
	List<Map<String, Object>> AccountMenuList(Map<String, Object> paramMap);
	List<Map<String, Object>> AccountIngredientsList(Map<String, Object> paramMap);
	List<Map<String, Object>> AccountDetailList(Map<String, Object> paramMap);
	List<Map<String, Object>> LikeMenuList(Map<String, Object> paramMap);
	int LikeMenuSave(Map<String, Object> paramMap);
	List<Map<String, Object>> LikeIngredientsList(Map<String, Object> paramMap);
	int LikeIngredientsSave(Map<String, Object> paramMap);
	int RecipeInfoDelete(Map<String, Object> paramMap);
	int RecipeImageDelete(Map<String, Object> paramMap);
	int RecipeVideoDelete(Map<String, Object> paramMap);
	int RecipeInfoSave(Map<String, Object> paramMap);
	int RecipeImageSave(Map<String, Object> paramMap);
	int RecipeVideoSave(Map<String, Object> paramMap);
	List<Map<String, Object>> RecipeList(Map<String, Object> paramMap);
	List<Map<String, Object>> ImageList(Map<String, Object> paramMap);
	List<Map<String, Object>> VideoList(Map<String, Object> paramMap);
}	
