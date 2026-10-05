package com.example.demo.service;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.SupplierCatalogMapper;

@Service
public class SupplierCatalogService {
	private final SupplierCatalogMapper mapper;

	public SupplierCatalogService(SupplierCatalogMapper mapper) {
		this.mapper = mapper;
	}

	public List<Map<String, Object>> suppliers(Map<String, Object> params) {
		return mapper.suppliers(params);
	}

	public List<Map<String, Object>> products(Map<String, Object> params) {
		return mapper.products(params);
	}

	public List<Map<String, Object>> prices(Map<String, Object> params) {
		return mapper.prices(params);
	}

	public List<Map<String, Object>> accountProducts(Map<String, Object> params) {
		return mapper.accountProducts(params);
	}

	public Map<String, Object> createSupplier(Map<String, Object> body) {
		require(body, "supplier_code", "supplier_name");
		mapper.insertSupplier(body);
		return result(body.get("supplier_id"));
	}

	public Map<String, Object> updateSupplier(Map<String, Object> body) {
		require(body, "supplier_id", "supplier_code", "supplier_name");
		return changed(mapper.updateSupplier(body), body.get("supplier_id"));
	}

	public Map<String, Object> deleteSupplier(Map<String, Object> body) {
		require(body, "supplier_id");
		return changed(mapper.deleteSupplier(body), body.get("supplier_id"));
	}

	public Map<String, Object> createProduct(Map<String, Object> body) {
		require(body, "supplier_id", "ingredient_id", "supplier_item_code", "product_name", "order_unit", "package_qty",
				"package_unit", "base_qty", "base_unit");
		mapper.insertProduct(body);
		return result(body.get("supplier_product_id"));
	}

	/**
	 * 공급상품과 최초 가격은 한 단위로 저장한다. 가격 저장이 실패하면 상품도 롤백하여 가격 없는 반쪽 상품이 남지 않게 한다.
	 */
	@Transactional
	public Map<String, Object> createProductWithPrice(Map<String, Object> body) {
		normalizeProductPrice(body);
		require(body, "supplier_id", "ingredient_id", "supplier_item_code", "product_name", "order_unit", "package_qty",
				"package_unit", "base_qty", "base_unit", "purchase_price", "effective_from");
		mapper.insertProduct(body);
		mapper.closeCurrentPrice(body);
		mapper.insertPrice(body);
		return result(body.get("supplier_product_id"));
	}

	@Transactional
	public Map<String, Object> updateProductWithPrice(Map<String, Object> body) {
		require(body, "supplier_product_id", "supplier_id", "ingredient_id", "supplier_item_code");
		Map<String, Object> stored = mapper.productForUpdate(body);
		if (stored == null)
			throw new IllegalArgumentException("수정할 공급상품을 찾을 수 없습니다.");
		for (String field : List.of("supplier_id", "ingredient_id", "supplier_item_code")) {
			if (!text(stored.get(field)).equals(text(body.get(field))))
				throw new IllegalArgumentException("기존 상품의 공급처·상품코드·식자재 연결은 변경할 수 없습니다.");
		}
		normalizeProductPrice(body);
		mapper.updateProduct(body);
		mapper.closeCurrentPrice(body);
		mapper.insertPrice(body);
		return result(body.get("supplier_product_id"));
	}

	private void normalizeProductPrice(Map<String, Object> body) {
		require(body, "ingredient_id", "product_name", "order_unit", "package_unit", "base_unit", "package_qty",
				"base_qty", "purchase_price");
		for (String field : List.of("package_qty", "base_qty", "purchase_price")) {
			BigDecimal value = new BigDecimal(text(body.get(field)));
			if (value.signum() <= 0)
				throw new IllegalArgumentException("포장수량·기준용량·가격은 0보다 커야 합니다.");
			body.put(field, value);
		}
		String baseUnit = mapper.ingredientBaseUnit(body);
		if (baseUnit == null)
			throw new IllegalArgumentException("식자재를 찾을 수 없습니다.");
		body.put("base_qty", QuantityUnits.convert((BigDecimal) body.get("base_qty"), body.get("base_unit"), baseUnit));
		body.put("base_unit", QuantityUnits.unit(baseUnit));
		// The editor saves the current price, including repeated edits on the same day.
		body.put("effective_from", LocalDateTime.now().withNano(0));
		body.put("effective_to", null);
	}

	public Map<String, Object> updateProduct(Map<String, Object> body) {
		require(body, "supplier_product_id");
		return changed(mapper.updateProduct(body), body.get("supplier_product_id"));
	}

	public Map<String, Object> deleteProduct(Map<String, Object> body) {
		require(body, "supplier_product_id");
		return changed(mapper.deleteProduct(body), body.get("supplier_product_id"));
	}

	@Transactional
	public Map<String, Object> createPrice(Map<String, Object> body) {
		require(body, "supplier_product_id", "purchase_price", "effective_from");
		mapper.closeCurrentPrice(body);
		mapper.insertPrice(body);
		return result(body.get("price_history_id"));
	}

	public Map<String, Object> updatePrice(Map<String, Object> body) {
		require(body, "price_history_id", "purchase_price", "effective_from");
		return changed(mapper.updatePrice(body), body.get("price_history_id"));
	}

	public Map<String, Object> deletePrice(Map<String, Object> body) {
		require(body, "price_history_id");
		return changed(mapper.deletePrice(body), body.get("price_history_id"));
	}

	@Transactional
	public Map<String, Object> createAccountProduct(Map<String, Object> body) {
		require(body, "account_id", "supplier_product_id");
		if ("Y".equalsIgnoreCase(text(body.get("preferred_yn"))))
			mapper.clearPreferredProduct(body);
		mapper.insertAccountProduct(body);
		return result(body.get("account_ingredient_product_id"));
	}

	@Transactional
	public Map<String, Object> updateAccountProduct(Map<String, Object> body) {
		require(body, "account_ingredient_product_id", "account_id");
		if ("Y".equalsIgnoreCase(text(body.get("preferred_yn"))))
			mapper.clearPreferredProduct(body);
		return changed(mapper.updateAccountProduct(body), body.get("account_ingredient_product_id"));
	}

	public Map<String, Object> deleteAccountProduct(Map<String, Object> body) {
		require(body, "account_ingredient_product_id");
		return changed(mapper.deleteAccountProduct(body), body.get("account_ingredient_product_id"));
	}

	private void require(Map<String, Object> body, String... fields) {
		for (String field : fields)
			if (text(body.get(field)).isBlank())
				throw new IllegalArgumentException(field + " is required.");
	}

	private String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private Map<String, Object> result(Object id) {
		return Map.of("code", 200, "message", "success", "id", id);
	}

	private Map<String, Object> changed(int count, Object id) {
		if (count == 0)
			throw new IllegalArgumentException("Target row was not found.");
		return result(id);
	}
}
