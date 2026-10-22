package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.MenuMapper;

@Service
public class MenuService {
	private static final AtomicLong RECIPE_ID_SEQUENCE = new AtomicLong(System.currentTimeMillis());

	MenuMapper menuMapper;
	private final StockAvailabilityService availability;
	private final AccountMenuRecipeService recipes;

	public MenuService(MenuMapper menuMapper, StockAvailabilityService availability, AccountMenuRecipeService recipes) {
		this.menuMapper = menuMapper;
		this.availability = availability;
		this.recipes = recipes;
	}

	public List<Map<String, Object>> MenuList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.MenuList(paramMap);
		return resultList;
	}

	public List<Map<String, Object>> DetailList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.DetailList(paramMap);
		return resultList;
	}

	public List<Map<String, Object>> IngredientsList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.IngredientsList(paramMap);
		return resultList;
	}

	// 메뉴 저장
	public int MenuSave(Map<String, Object> paramMap) {
		return menuMapper.MenuSave(paramMap);
	};

	// 식재료 저장
	public int IngredientsSave(Map<String, Object> paramMap) {
		normalizeRecipe(paramMap);
		Object recipeId = paramMap.get("recipe_id");
		if (recipeId == null || recipeId.toString().isBlank()) {
			Long existingRecipeId = menuMapper.RecipeId(paramMap);
			paramMap.put("recipe_id",
					existingRecipeId != null ? existingRecipeId : RECIPE_ID_SEQUENCE.incrementAndGet());
		}
		return menuMapper.IngredientsSave(paramMap);
	};

	// 거래처 레시피는 거래처 식재료 기준단위(거래처에서 추가한 식재료는 본사에 없다)
	private void normalizeAccountRecipe(Map<String, Object> row) {
		String base = menuMapper.accountIngredientBaseUnit(row);
		if (base == null)
			throw new IllegalArgumentException("식재료 기준단위를 확인해주세요.");
		QuantityUnits.recipe(row, base);
	}

	private void normalizeRecipe(Map<String, Object> row) {
		String base = menuMapper.ingredientBaseUnit(row);
		if (base == null)
			throw new IllegalArgumentException("식재료 기준단위를 확인해주세요.");
		QuantityUnits.recipe(row, base);
	}

	// 거래처 메뉴 저장
	public int AccountMenuSave(Map<String, Object> paramMap) {
		return menuMapper.AccountMenuSave(paramMap);
	};

	// 거래처 메뉴 식재료 저장
	public int AccountIngredientsSave(Map<String, Object> paramMap) {
		normalizeAccountRecipe(paramMap);
		return menuMapper.AccountIngredientsSave(paramMap);
	};

	// 거래처 식재료 저장
	public int AccountIngredientsMasterSave(Map<String, Object> paramMap) {
		return menuMapper.AccountIngredientsMasterSave(paramMap);
	};

	// 거래처 재고 최초 저장
	public int AccountInventorySave(Map<String, Object> paramMap) {
		return menuMapper.AccountInventorySave(paramMap);
	};

	// 거래처 메뉴 조회
	public List<Map<String, Object>> AccountMenuList(Map<String, Object> paramMap) {
		validateCostDate(paramMap);
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.AccountMenuList(paramMap);
		return resultList;
	}

	// 거래처 식자재 조회
	public List<Map<String, Object>> AccountIngredientsList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.AccountIngredientsList(paramMap);
		return resultList;
	}

	// 거래처 메뉴 연결 리스트 조회
	public List<Map<String, Object>> AccountDetailList(Map<String, Object> paramMap) {
		validateCostDate(paramMap);
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.AccountDetailList(paramMap);
		availability.enrich(resultList, paramMap, true);
		var origins = recipes.origins(paramMap);
		for (var row : resultList) {
			origins.stream().filter(o -> java.util.Objects.equals(o.get("menu_id"),row.get("menu_id")) && java.util.Objects.equals(o.get("ingredient_id"),row.get("ingredient_id")))
				.findFirst().ifPresent(o -> row.put("origin_name",o.get("origin_name")));
		}
		return resultList;
	}

	private void validateCostDate(Map<String, Object> params) {
		Object value = params.get("cost_date");
		if (value == null) return;
		try {
			java.time.LocalDate.parse(value.toString());
		} catch (java.time.format.DateTimeParseException ex) {
			throw new org.springframework.web.server.ResponseStatusException(
				org.springframework.http.HttpStatus.BAD_REQUEST, "원가 기준일은 YYYY-MM-DD 형식이어야 합니다.");
		}
	}

	// 본사 메뉴의 거래처 기준 예상 원가(식단표 편집기에서 본사 메뉴 선택용)
	public List<Map<String, Object>> HqMenuCosts(Map<String, Object> paramMap) {
		Object account = paramMap.get("account_id");
		if (account == null || account.toString().isBlank())
			throw new IllegalArgumentException("거래처 선택 요망");
		return menuMapper.HqMenuCosts(paramMap);
	}

	// 나만의 메뉴 조회 (본사 메뉴. 주인은 FavoriteOwner가 채운 favorite_owner_*)
	public List<Map<String, Object>> LikeMenuList(Map<String, Object> paramMap) {
		return menuMapper.LikeMenuList(paramMap);
	}

	// 나만의 메뉴 지정(like=Y)·해제(like=N). 이미 같은 상태여도 성공으로 본다.
	public int LikeMenuSave(Map<String, Object> paramMap) {
		Object menuId = paramMap.get("menu_id");
		if (menuId == null || menuId.toString().isBlank())
			throw new IllegalArgumentException("메뉴 선택 요망");
		if ("N".equals(String.valueOf(paramMap.get("like"))))
			menuMapper.LikeMenuDelete(paramMap);
		else
			menuMapper.LikeMenuSave(paramMap);
		return 1;
	}

	@Transactional
	public int RecipeSave(Map<String, Object> recipeInfo, List<Map<String, Object>> imageList,
			List<Map<String, Object>> videoList) {
		menuMapper.RecipeImageDelete(recipeInfo);
		menuMapper.RecipeVideoDelete(recipeInfo);
		menuMapper.RecipeInfoDelete(recipeInfo);

		int result = menuMapper.RecipeInfoSave(recipeInfo);

		if (imageList != null) {
			for (Map<String, Object> image : imageList) {
				menuMapper.RecipeImageSave(image);
			}
		}

		if (videoList != null) {
			for (Map<String, Object> video : videoList) {
				menuMapper.RecipeVideoSave(video);
			}
		}

		return result;
	};

	// 레시피 조회
	public List<Map<String, Object>> RecipeList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.RecipeList(paramMap);
		return resultList;
	}

	// 이미지 조회
	public List<Map<String, Object>> ImageList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.ImageList(paramMap);
		return resultList;
	}

	// 비디오 조회
	public List<Map<String, Object>> VideoList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.VideoList(paramMap);
		return resultList;
	}
}
