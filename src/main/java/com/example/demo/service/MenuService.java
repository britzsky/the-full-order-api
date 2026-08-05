package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.MenuMapper;

@Service
public class MenuService {

	MenuMapper menuMapper;
	
	public MenuService(MenuMapper menuMapper) {
		this.menuMapper = menuMapper;
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
		return menuMapper.IngredientsSave(paramMap);
	};
	
	// 거래처 메뉴 저장
	public int AccountMenuSave(Map<String, Object> paramMap) {
		return menuMapper.AccountMenuSave(paramMap);
	};
	
	// 거래처 메뉴 식재료 저장
	public int AccountIngredientsSave(Map<String, Object> paramMap) {
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
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.AccountDetailList(paramMap);
		return resultList;
	}
	
	// 나만의 메뉴 조회
	public List<Map<String, Object>> LikeMenuList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.LikeMenuList(paramMap);
		return resultList;
	}
	
	// 나만의 메뉴 저장
	public int LikeMenuSave(Map<String, Object> paramMap) {
		return menuMapper.LikeMenuSave(paramMap);
	};
	
	// 나만의 식자재 조회
	public List<Map<String, Object>> LikeIngredientsList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuMapper.LikeIngredientsList(paramMap);
		return resultList;
	}
	
	// 나만의 식자재 저장
	public int LikeIngredientsSave(Map<String, Object> paramMap) {
		return menuMapper.LikeIngredientsSave(paramMap);
	};
	
	@Transactional
	public int RecipeSave(Map<String, Object> recipeInfo, List<Map<String, Object>> imageList, List<Map<String, Object>> videoList) {
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
