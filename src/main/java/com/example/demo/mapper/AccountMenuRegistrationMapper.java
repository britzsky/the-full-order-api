package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 식단표 PDF 등에서 거래처 메뉴를 새로 등록·본사 메뉴 가져오기·본사 메뉴로 반영 */
@Mapper
public interface AccountMenuRegistrationMapper {
	List<Map<String, Object>> activeAccountMenus(Map<String, Object> p);

	int menuIdTaken(Map<String, Object> p);

	Map<String, Object> accountMenu(Map<String, Object> p);

	List<Map<String, Object>> accountRecipe(Map<String, Object> p);

	Map<String, Object> standardMenu(Map<String, Object> p);

	List<Map<String, Object>> standardRecipe(Map<String, Object> p);

	List<Map<String, Object>> activeStandardMenus(Map<String, Object> p);

	int insertStandardMenu(Map<String, Object> p);

	int insertStandardRecipeRow(Map<String, Object> p);

	List<Map<String, Object>> accountIngredients(Map<String, Object> p);

	int ingredientIdTaken(Map<String, Object> p);

	List<Map<String, Object>> standardIngredients(Map<String, Object> p);

	int insertStandardIngredient(Map<String, Object> p);
}
