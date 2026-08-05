package com.example.demo.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.service.MenuService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@RestController
public class MenuController {

	private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:([^;]+);base64,(.+)$");

	private final MenuService menuService;
	private final String uploadDir;
	private final ObjectMapper objectMapper;

	@Autowired
	public MenuController(
			MenuService menuService,
			@Value("${file.upload-dir}") String uploadDir,
			ObjectMapper objectMapper) {
		this.menuService = menuService;
		this.uploadDir = uploadDir;
		this.objectMapper = objectMapper;
	}
	
	/*
	 * method : MenuList
	 * comment : 메뉴 리스트 조회
	 */
	@GetMapping("/Menu/MenuList")
	public String MenuList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.MenuList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : DetailList
	 * comment : 메뉴 연결 리스트 조회
	 */
	@GetMapping("/Menu/DetailList")
	public String DetailList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.DetailList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : DetailList
	 * comment : 식자재 리스트 조회
	 */
	@GetMapping("/Menu/IngredientsList")
	public String IngredientsList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.IngredientsList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : MenuSave
	 * comment : 메뉴 저장
	 */
	@PostMapping(value = "/Menu/MenuSave", consumes = MediaType.APPLICATION_JSON_VALUE)
	public String MenuSave(@RequestBody Map<String, Object> payload) {
		List<Map<String, Object>> menuList = (List<Map<String, Object>>) payload.get("menus");
		List<Map<String, Object>> detailList = (List<Map<String, Object>>) payload.get("menu_details");
		return saveMenuPayload(menuList, detailList);
	}
	
	@PostMapping(value = "/Menu/MenuSave", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public String MenuSaveMultipart(
			@RequestParam Map<String, String> payload,
			@RequestPart(value = "menu_img", required = false) MultipartFile menuImg) {
		try {
			List<Map<String, Object>> menuList = parseMapList(payload.get("menus"));
			List<Map<String, Object>> detailList = parseMapList(payload.get("menu_details"));
			
			if (menuList == null) {
				Map<String, Object> menu = new HashMap<>(payload);
				menu.remove("menus");
				menu.remove("menu_details");
				menuList = List.of(menu);
			}
			
			if (menuImg != null && !menuImg.isEmpty() && !menuList.isEmpty()) {
				menuList.get(0).put("menu_img_file", menuImg);
			}
			
			return saveMenuPayload(menuList, detailList);
		} catch (Exception e) {
			JsonObject obj = new JsonObject();
			obj.addProperty("code", 500);
			obj.addProperty("message", e.getMessage());
			return obj.toString();
		}
	}
	
	private String saveMenuPayload(List<Map<String, Object>> menuList, List<Map<String, Object>> detailList) {
		int iResult = 0;
	    
		if (menuList != null) {
			for (Map<String, Object> paramMap : menuList) {
				paramMap.put("menu_img", saveMenuImageFile(paramMap));
				iResult += menuService.MenuSave(paramMap);
			}
		}
		
		if (detailList != null) {
			for (Map<String, Object> paramMap : detailList) {
				iResult += menuService.IngredientsSave(paramMap);
			}
		}
	    
		JsonObject obj = new JsonObject();
    	
		if (iResult > 0) {
			obj.addProperty("code", 200);
			obj.addProperty("message", "success");
		} else {
			obj.addProperty("code", 400);
			obj.addProperty("message", "fail");
		}
    	
		return obj.toString();
	}
	
	private List<Map<String, Object>> parseMapList(String json) throws JsonProcessingException {
		if (json == null || json.isBlank()) {
			return null;
		}
		
		return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
		});
	}
	
	private String saveMenuImageFile(Map<String, Object> menu) {
		MultipartFile menuImgFile = (MultipartFile) menu.get("menu_img_file");
		if (menuImgFile != null && !menuImgFile.isEmpty()) {
			return saveMenuImageFile(menuImgFile);
		}
		
		Object menuImg = menu.get("menu_img");
		String fallbackUrl = imageValue(menuImg, "file_url", "url");
		String originalName = firstString(
				imageMapValue(menuImg, "file_name", "name"),
				fileNameFromImageReference(fallbackUrl),
				fileNameFromImageReference(imageText(menuImg)));
		ImageBytes imageBytes = getMenuImageBytes(menuImg);
		String staticPath = new File(uploadDir).getAbsolutePath();
		String basePath = staticPath + File.separator + "menu" + File.separator;
		
		if (imageBytes == null) {
			String safeFileName = sanitizeFileName(originalName);
			String fileUrl = normalizeMenuImageUrl(fallbackUrl, safeFileName);
			
			if (safeFileName != null) {
				try {
					Path dirPath = Paths.get(basePath);
					Files.createDirectories(dirPath);
					
					Path targetPath = dirPath.resolve(safeFileName).normalize();
					Path sourcePath = findExistingImagePath(staticPath, null, fallbackUrl, safeFileName);
					
					if (sourcePath != null && !sourcePath.equals(targetPath)) {
						Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
					}
					
					if (Files.exists(targetPath)) {
						fileUrl = "/image/menu/" + safeFileName;
					}
				} catch (IOException e) {
					throw new IllegalStateException("Menu image file copy failed.", e);
				}
			}
			
			return fileUrl;
		}
		
		try {
			Path dirPath = Paths.get(basePath);
			Files.createDirectories(dirPath);
			
			System.out.println("staticPath :: " + staticPath);
			System.out.println("basePath :: " + basePath);
			
			String extension = getImageExtension(originalName, imageBytes.contentType());
			String safeOriginalName = sanitizeFileName(originalName);
			String fileName = safeOriginalName == null
					? UUID.randomUUID() + extension
					: UUID.randomUUID() + "_" + appendExtensionIfMissing(safeOriginalName, extension);
			Path filePath = dirPath.resolve(fileName).normalize();
			Files.write(filePath, imageBytes.bytes());
			
			String fileUrl = "/image/menu/" + fileName;
			
			System.out.println("menu_img :: " + fileUrl);
			
			return fileUrl;
		} catch (IOException e) {
			throw new IllegalStateException("Menu image file save failed.", e);
		}
	}
	
	private String saveMenuImageFile(MultipartFile imageFile) {
		String staticPath = new File(uploadDir).getAbsolutePath();
		String basePath = staticPath + File.separator + "menu" + File.separator;
		
		try {
			Path dirPath = Paths.get(basePath);
			Files.createDirectories(dirPath);
			
			System.out.println("staticPath :: " + staticPath);
			System.out.println("basePath :: " + basePath);
			
			String originalName = imageFile.getOriginalFilename();
			String extension = getImageExtension(originalName, imageFile.getContentType());
			String safeOriginalName = sanitizeFileName(originalName);
			String fileName = safeOriginalName == null
					? UUID.randomUUID() + extension
					: UUID.randomUUID() + "_" + appendExtensionIfMissing(safeOriginalName, extension);
			Path filePath = dirPath.resolve(fileName).normalize();
			imageFile.transferTo(filePath);
			
			String fileUrl = "/image/menu/" + fileName;
			
			System.out.println("menu_img :: " + fileUrl);
			
			return fileUrl;
		} catch (IOException e) {
			throw new IllegalStateException("Menu image file save failed.", e);
		}
	}
	
	/*
	 * method : AccountMenuSave
	 * comment : 거래처 메뉴 저장
	 */
	@SuppressWarnings({ "null", "unchecked" })
	@PostMapping("/Menu/AccountMenuSave")
	public String AccountMenuSave(@RequestBody Map<String, Object> payload) {
		List<Map<String, Object>> menuList = (List<Map<String, Object>>) payload.get("added_menus");
		List<Map<String, Object>> detailList = (List<Map<String, Object>>) payload.get("menu_details");
		List<Map<String, Object>> removeList = (List<Map<String, Object>>) payload.get("removed_menus");
		List<Map<String, Object>> ingredient_detail = (List<Map<String, Object>>) payload.get("ingredient_detail");
		
		int iResult = 0;
	    
		if (menuList != null && menuList.size() > 0) {
			for (Map<String, Object> paramMap : menuList) {
				iResult += menuService.AccountMenuSave(paramMap);
			}
			
			if (detailList != null && detailList.size() > 0) {
				for (Map<String, Object> paramMap : detailList) {
					iResult += menuService.AccountIngredientsSave(paramMap);
					iResult += menuService.AccountInventorySave(paramMap);
				}
			}
		}
		
		if (removeList != null && removeList.size() > 0) {
			for (Map<String, Object> paramMap : removeList) {
				iResult += menuService.AccountMenuSave(paramMap);
			}
		}
		
		if (ingredient_detail != null && ingredient_detail.size() > 0) {
			for (Map<String, Object> paramMap : ingredient_detail) {
				iResult += menuService.AccountIngredientsMasterSave(paramMap);
			}
		}
	    
		JsonObject obj = new JsonObject();
    	
		if (iResult > 0) {
			obj.addProperty("code", 200);
			obj.addProperty("message", "success");
		} else {
			obj.addProperty("code", 400);
			obj.addProperty("message", "fail");
		}
    	
		return obj.toString();
	}
	
	/*
	 * method : AccountMenuList
	 * comment : 거래처 메뉴 조회
	 */
	@GetMapping("/Menu/AccountMenuList")
	public String AccountMenuList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.AccountMenuList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : AccountDetailList
	 * comment : 거래처 메뉴 연결 리스트 조회
	 */
	@GetMapping("/Menu/AccountDetailList")
	public String AccountDetailList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.AccountDetailList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : AccountIngredientsList
	 * comment : 거래처 식자재 리스트 조회
	 */
	@GetMapping("/Menu/AccountIngredientsList")
	public String AccountIngredientsList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.AccountIngredientsList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : LikeMenuList
	 * comment : 나만의 메뉴 조회
	 */
	@GetMapping("/Menu/LikeMenuList")
	public String LikeMenuList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.LikeMenuList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : LikeMenuSave
	 * comment : 나만의 메뉴 저장
	 */
	@PostMapping("/Menu/LikeMenuSave")
	public String LikeMenuSave(@RequestBody Map<String, Object> paramMap) {
		int iResult = menuService.LikeMenuSave(paramMap);
		JsonObject obj = new JsonObject();
    	
		if (iResult > 0) {
			obj.addProperty("code", 200);
			obj.addProperty("message", "success");
		} else {
			obj.addProperty("code", 400);
			obj.addProperty("message", "fail");
		}
    	
		return obj.toString();
	}
	
	/*
	 * method : LikeIngredientsList
	 * comment : 나만의 식자재 조회
	 */
	@GetMapping("/Menu/LikeIngredientsList")
	public String LikeIngredientsList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = menuService.LikeIngredientsList(paramMap);

		return new Gson().toJson(resultList);
	}
	
	/*
	 * method : LikeIngredientsSave
	 * comment : 나만의 식자재 저장
	 */
	@PostMapping("/Menu/LikeIngredientsSave")
	public String LikeIngredientsSave(@RequestBody Map<String, Object> paramMap) {
		int iResult = menuService.LikeIngredientsSave(paramMap);
		JsonObject obj = new JsonObject();
    	
		if (iResult > 0) {
			obj.addProperty("code", 200);
			obj.addProperty("message", "success");
		} else {
			obj.addProperty("code", 400);
			obj.addProperty("message", "fail");
		}
    	
		return obj.toString();
	}
	
	/*
	 * method : RecipeSave
	 * comment : 레시피 저장
	 */
	@PostMapping("/Menu/RecipeSave")
	public String RecipeSave(@RequestBody Map<String, Object> paramMap) {
		JsonObject obj = new JsonObject();
		
		if (isBlank(paramMap.get("menu_id"))) {
			obj.addProperty("code", 400);
			obj.addProperty("message", "menu_id is required.");
			return obj.toString();
		}
		
		if (isBlank(paramMap.get("menu_name"))) {
			obj.addProperty("code", 400);
			obj.addProperty("message", "menu_name is required.");
			return obj.toString();
		}
		
		try {
			if (isBlank(paramMap.get("recipe_id"))) {
				paramMap.put("recipe_id", System.currentTimeMillis());
			}
			
			if (isBlank(paramMap.get("title"))) {
				paramMap.put("title", paramMap.get("menu_name") + " recipe");
			}
			
			List<Map<String, Object>> imageList = createRecipeImages(paramMap);
			List<Map<String, Object>> videoList = createRecipeVideos(paramMap);
			
			paramMap.put("ingredients_json", toJson(paramMap.get("ingredients")));
			paramMap.put("steps_json", toJson(paramMap.get("steps")));
			paramMap.put("tips_json", toJson(paramMap.get("tips")));
			paramMap.put("storage_json", toJson(paramMap.get("storage")));
			paramMap.put("allergens_json", toJson(paramMap.get("allergens")));
			paramMap.put("recipe_json", toJson(createRecipeJson(paramMap, imageList, videoList)));
			
			int iResult = menuService.RecipeSave(paramMap, imageList, videoList);
	    	
			if (iResult > 0) {
				obj.addProperty("code", 200);
				obj.addProperty("message", "success");
				obj.addProperty("recipe_id", paramMap.get("recipe_id").toString());
			} else {
				obj.addProperty("code", 400);
				obj.addProperty("message", "fail");
			}
		} catch (Exception e) {
			obj.addProperty("code", 500);
			obj.addProperty("message", e.getMessage());
		}
    	
		return obj.toString();
	}
	
	private List<Map<String, Object>> createRecipeImages(Map<String, Object> recipe) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		List<Map<String, Object>> imageList = (List<Map<String, Object>>) recipe.get("images");
		
		if (imageList == null) {
			return resultList;
		}
		
		for (int i = 0; i < imageList.size(); i++) {
			Map<String, Object> image = imageList.get(i);
			RecipeImageFile savedFile = saveRecipeImageFile(recipe, image);
			Map<String, Object> saveMap = new HashMap<>();
			
			saveMap.put("recipe_id", recipe.get("recipe_id"));
			saveMap.put("menu_id", recipe.get("menu_id"));
			saveMap.put("file_id", savedFile.fileId());
			saveMap.put("file_name", savedFile.fileName());
			saveMap.put("file_url", savedFile.fileUrl());
			saveMap.put("file_path", savedFile.filePath());
			saveMap.put("file_size", savedFile.fileSize());
			saveMap.put("is_primary", toPrimaryYn(image.get("is_primary")));
			saveMap.put("sort_order", image.get("sort_order") == null ? i : image.get("sort_order"));
			
			resultList.add(saveMap);
		}
		
		return resultList;
	}
	
	private RecipeImageFile saveRecipeImageFile(Map<String, Object> recipe, Map<String, Object> image) {
		String fileId = firstString(image.get("file_id"));
		if (fileId == null) {
			fileId = UUID.randomUUID().toString();
		}
		
		String originalName = firstString(image.get("file_name"), image.get("name"));
		String fallbackUrl = firstString(image.get("file_url"), image.get("url"));
		String fallbackPath = firstString(image.get("file_path"));
		Long fallbackSize = toLong(image.get("file_size"));
		ImageBytes imageBytes = getImageBytes(image);
		String staticPath = new File(uploadDir).getAbsolutePath();
		String basePath = staticPath + File.separator + "recipe" + File.separator;
		
		if (imageBytes == null) {
			String fileName = firstString(originalName, fileNameFromRecipeUrl(fallbackUrl));
			String safeFileName = sanitizeFileName(fileName);
			String fileUrl = normalizeRecipeImageUrl(fallbackUrl, safeFileName);
			String filePath = fallbackPath;
			
			if (safeFileName != null) {
				try {
					Path dirPath = Paths.get(basePath);
					Files.createDirectories(dirPath);
					
					Path targetPath = dirPath.resolve(safeFileName).normalize();
					Path sourcePath = findExistingImagePath(staticPath, fallbackPath, fallbackUrl, safeFileName);
					
					if (sourcePath != null && !sourcePath.equals(targetPath)) {
						Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
					}
					
					if (Files.exists(targetPath)) {
						filePath = targetPath.toString();
						fallbackSize = Files.size(targetPath);
						fileUrl = "/image/recipe/" + safeFileName;
					}
				} catch (IOException e) {
					throw new IllegalStateException("Recipe image file copy failed.", e);
				}
			}
			
			if (filePath == null && safeFileName != null) {
				filePath = Paths.get(basePath).resolve(safeFileName).normalize().toString();
			}
			
			return new RecipeImageFile(fileId, safeFileName, fileUrl, filePath, fallbackSize);
		}
		
		try {
			Path dirPath = Paths.get(basePath);
			Files.createDirectories(dirPath);
			
			String extension = getImageExtension(originalName, imageBytes.contentType());
			String safeOriginalName = sanitizeFileName(originalName);
			String fileName = safeOriginalName == null
					? UUID.randomUUID() + extension
					: UUID.randomUUID() + "_" + appendExtensionIfMissing(safeOriginalName, extension);
			Path filePath = dirPath.resolve(fileName).normalize();
			Files.write(filePath, imageBytes.bytes());
			
			String fileUrl = "/image/recipe/" + fileName;
			
			return new RecipeImageFile(fileId, fileName, fileUrl, filePath.toString(), (long) imageBytes.bytes().length);
		} catch (IOException e) {
			throw new IllegalStateException("Recipe image file save failed.", e);
		}
	}
	// 이미지 용량
	private ImageBytes getImageBytes(Map<String, Object> image) {
		String data = firstString(image.get("data_url"), image.get("base64"), image.get("image_data"));
		
		if (data == null) {
			String url = firstString(image.get("url"), image.get("file_url"));
			if (url != null && url.startsWith("data:")) {
				data = url;
			}
		}
		
		if (data == null) {
			return null;
		}
		
		Matcher matcher = DATA_URL_PATTERN.matcher(data);
		if (matcher.matches()) {
			return new ImageBytes(Base64.getDecoder().decode(matcher.group(2)), matcher.group(1));
		}
		
		return new ImageBytes(Base64.getDecoder().decode(data), null);
	}
	
	private ImageBytes getMenuImageBytes(Object menuImg) {
		String data = imageMapValue(menuImg, "data_url", "base64", "image_data");
		
		if (data == null && menuImg instanceof String text && text.startsWith("data:")) {
			data = text;
		}
		
		if (data == null) {
			return null;
		}
		
		Matcher matcher = DATA_URL_PATTERN.matcher(data);
		if (matcher.matches()) {
			return new ImageBytes(Base64.getDecoder().decode(matcher.group(2)), matcher.group(1));
		}
		
		return new ImageBytes(Base64.getDecoder().decode(data), null);
	}
	
	private String imageValue(Object image, String... keys) {
		if (image instanceof Map<?, ?> imageMap) {
			return imageMapValue(imageMap, keys);
		}
		
		return firstString(image);
	}
	
	private String imageMapValue(Object image, String... keys) {
		if (!(image instanceof Map<?, ?> imageMap)) {
			return null;
		}
		
		for (String key : keys) {
			Object value = imageMap.get(key);
			if (value != null && !value.toString().isBlank()) {
				return value.toString();
			}
		}
		
		return null;
	}
	
	private String imageText(Object image) {
		return image instanceof Map<?, ?> ? null : firstString(image);
	}
	// 비디오 저장
	private List<Map<String, Object>> createRecipeVideos(Map<String, Object> recipe) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		List<Map<String, Object>> videoList = (List<Map<String, Object>>) recipe.get("videos");
		
		if (videoList == null) {
			return resultList;
		}
		
		for (int i = 0; i < videoList.size(); i++) {
			Map<String, Object> video = videoList.get(i);
			Map<String, Object> saveMap = new HashMap<>();
			
			saveMap.put("recipe_id", recipe.get("recipe_id"));
			saveMap.put("menu_id", recipe.get("menu_id"));
			saveMap.put("title", video.get("title"));
			saveMap.put("url", video.get("url"));
			saveMap.put("description", video.get("description"));
			saveMap.put("sort_order", video.get("sort_order") == null ? i : video.get("sort_order"));
			saveMap.put("user_id", recipe.get("user_id"));
			
			resultList.add(saveMap);
		}
		
		return resultList;
	}
	
	private Map<String, Object> createRecipeJson(
			Map<String, Object> recipe,
			List<Map<String, Object>> imageList,
			List<Map<String, Object>> videoList) {
		Map<String, Object> recipeJson = new HashMap<>(recipe);
		recipeJson.put("images", imageList);
		recipeJson.put("videos", videoList);
		recipeJson.remove("ingredients_json");
		recipeJson.remove("steps_json");
		recipeJson.remove("tips_json");
		recipeJson.remove("storage_json");
		recipeJson.remove("allergens_json");
		recipeJson.remove("recipe_json");
		
		return recipeJson;
	}
	
	private String toJson(Object value) throws JsonProcessingException {
		if (value == null) {
			return null;
		}
		
		return objectMapper.writeValueAsString(value);
	}
	
	private String getImageExtension(String fileName, String contentType) {
		if (fileName != null) {
			int dotIndex = fileName.lastIndexOf('.');
			if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
				String extension = fileName.substring(dotIndex + 1).replaceAll("[^A-Za-z0-9]", "").toLowerCase();
				if (!extension.isBlank()) {
					return "." + extension;
				}
			}
		}
		
		if ("image/png".equalsIgnoreCase(contentType)) {
			return ".png";
		}
		
		if ("image/webp".equalsIgnoreCase(contentType)) {
			return ".webp";
		}
		
		return ".jpg";
	}
	
	private String sanitizeFileName(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return null;
		}
		
		String sanitized = Paths.get(fileName).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
		return sanitized.isBlank() ? null : sanitized;
	}
	
	private String appendExtensionIfMissing(String fileName, String extension) {
		int dotIndex = fileName.lastIndexOf('.');
		if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
			return fileName;
		}
		
		return fileName + extension;
	}
	
	private Path findExistingImagePath(String staticPath, String filePath, String fileUrl, String fileName) {
		Path fromFilePath = existingPath(filePath);
		if (fromFilePath != null) {
			return fromFilePath;
		}
		
		Path fromUrl = pathFromImageUrl(staticPath, fileUrl);
		if (fromUrl != null && Files.exists(fromUrl)) {
			return fromUrl;
		}
		
		Path menuPath = Paths.get(staticPath, "menu", fileName).normalize();
		if (Files.exists(menuPath)) {
			return menuPath;
		}
		
		Path recipePath = Paths.get(staticPath, "recipe", fileName).normalize();
		if (Files.exists(recipePath)) {
			return recipePath;
		}
		
		Path rootPath = Paths.get(staticPath, fileName).normalize();
		return Files.exists(rootPath) ? rootPath : null;
	}
	
	private Path existingPath(String filePath) {
		if (filePath == null || filePath.isBlank()) {
			return null;
		}
		
		Path path = Paths.get(filePath).normalize();
		return Files.exists(path) ? path : null;
	}
	
	private Path pathFromImageUrl(String staticPath, String fileUrl) {
		if (fileUrl == null || fileUrl.isBlank() || fileUrl.startsWith("data:")) {
			return null;
		}
		
		String path = fileUrl.split("\\?", 2)[0].split("#", 2)[0].replace("\\", "/");
		if (path.startsWith("/image/")) {
			return Paths.get(staticPath, path.substring("/image/".length())).normalize();
		}
		
		return null;
	}
	
	private String normalizeRecipeImageUrl(String url, String fileName) {
		String value = firstString(url, fileName);
		if (value == null || value.startsWith("data:")) {
			return null;
		}
		
		if (value.startsWith("/image/recipe/")) {
			return value;
		}
		
		if (value.startsWith("/image/")) {
			return value;
		}
		
		return "/image/recipe/" + fileNameFromRecipeUrl(value);
	}
	
	private String normalizeMenuImageUrl(String url, String fileName) {
		String value = firstString(url, fileName);
		if (value == null || value.startsWith("data:")) {
			return null;
		}
		
		if (value.startsWith("/image/menu/")) {
			return value;
		}
		
		if (value.startsWith("/image/")) {
			return value;
		}
		
		return "/image/menu/" + fileNameFromRecipeUrl(value);
	}
	
	private String fileNameFromRecipeUrl(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		
		String path = value.split("\\?", 2)[0].split("#", 2)[0].replace("\\", "/");
		int slashIndex = path.lastIndexOf('/');
		return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
	}
	
	private String fileNameFromImageReference(String value) {
		if (value == null || value.isBlank() || value.startsWith("data:")) {
			return null;
		}
		
		return fileNameFromRecipeUrl(value);
	}
	
	private String toPrimaryYn(Object value) {
		if (Boolean.TRUE.equals(value)) {
			return "Y";
		}
		
		if (value == null) {
			return "N";
		}
		
		String text = value.toString();
		return "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text) ? "Y" : "N";
	}
	
	private boolean isBlank(Object value) {
		return value == null || value.toString().isBlank();
	}
	
	private String firstString(Object... values) {
		for (Object value : values) {
			if (value != null && !value.toString().isBlank()) {
				return value.toString();
			}
		}
		
		return null;
	}
	
	private Long toLong(Object value) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		
		if (value instanceof Number number) {
			return number.longValue();
		}
		
		return Long.parseLong(value.toString());
	}
	
	private record ImageBytes(byte[] bytes, String contentType) {
	}
	
	private record RecipeImageFile(String fileId, String fileName, String fileUrl, String filePath, Long fileSize) {
	}
	
	/*
	 * method : RecipeList
	 * comment : 레시피 조회
	 */
	@GetMapping("/Menu/RecipeList")
	public String RecipeList(@RequestParam Map<String, Object> paramMap) {
	    List<Map<String, Object>> recipeList = menuService.RecipeList(paramMap);
	    List<Map<String, Object>> imageList = menuService.ImageList(paramMap);
	    List<Map<String, Object>> videoList = menuService.VideoList(paramMap);

	    Map<String, Object> result = new HashMap<>();
	    Map<String, Object> recipe = new HashMap<>();

	    if (recipeList != null && !recipeList.isEmpty()) {
	        recipe.putAll(recipeList.get(0));
	    }

	    recipe.put("images", imageList != null ? imageList : new ArrayList<>());
	    recipe.put("videos", videoList != null ? videoList : new ArrayList<>());

	    result.put("recipe", recipe);
	    result.put("meta", Map.of(
	        "source", "local",
	        "fallback_used", true
	    ));

	    return new Gson().toJson(result);
	}
}
