package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MenuMapper {
	String ingredientBaseUnit(Map<String, Object> params);
	String accountIngredientBaseUnit(Map<String, Object> row);

	String NowDateKey();

	List<Map<String, Object>> MenuList(Map<String, Object> paramMap);

	List<Map<String, Object>> DetailList(Map<String, Object> paramMap);

	List<Map<String, Object>> IngredientsList(Map<String, Object> paramMap);

	Long RecipeId(Map<String, Object> paramMap);

	int MenuSave(Map<String, Object> paramMap);

	int IngredientsSave(Map<String, Object> paramMap);

	int AccountMenuSave(Map<String, Object> paramMap);

	int AccountIngredientsSave(Map<String, Object> paramMap);

	int AccountIngredientsMasterSave(Map<String, Object> paramMap);

	int AccountInventorySave(Map<String, Object> paramMap);

	List<Map<String, Object>> AccountMenuList(Map<String, Object> paramMap);

	List<Map<String, Object>> AccountIngredientsList(Map<String, Object> paramMap);

	List<Map<String, Object>> AccountDetailList(Map<String, Object> paramMap);

	// 거래처 식재료 하나의 편성 단가(직접 입력·입고 단가·본사 표준 단가)
	Map<String, Object> ingredientPlanningPrice(Map<String, Object> paramMap);

	// 나만의 메뉴(favorite_owner_type/favorite_owner_id는 FavoriteOwner가 채운다)
	List<Map<String, Object>> LikeMenuList(Map<String, Object> paramMap);

	int LikeMenuSave(Map<String, Object> paramMap);

	int LikeMenuDelete(Map<String, Object> paramMap);

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
