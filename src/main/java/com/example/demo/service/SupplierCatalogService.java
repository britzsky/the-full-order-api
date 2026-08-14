package com.example.demo.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.SupplierCatalogMapper;

@Service
public class SupplierCatalogService {
    private final SupplierCatalogMapper mapper;

    public SupplierCatalogService(SupplierCatalogMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> suppliers(Map<String, Object> params) { return mapper.suppliers(params); }
    public List<Map<String, Object>> products(Map<String, Object> params) { return mapper.products(params); }
    public List<Map<String, Object>> prices(Map<String, Object> params) { return mapper.prices(params); }
    public List<Map<String, Object>> accountProducts(Map<String, Object> params) { return mapper.accountProducts(params); }

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
        require(body, "supplier_id", "ingredient_id", "supplier_item_code", "product_name",
                "order_unit", "package_qty", "package_unit", "base_qty", "base_unit");
        mapper.insertProduct(body);
        return result(body.get("supplier_product_id"));
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
        if ("Y".equalsIgnoreCase(text(body.get("preferred_yn")))) mapper.clearPreferredProduct(body);
        mapper.insertAccountProduct(body);
        return result(body.get("account_ingredient_product_id"));
    }

    @Transactional
    public Map<String, Object> updateAccountProduct(Map<String, Object> body) {
        require(body, "account_ingredient_product_id", "account_id");
        if ("Y".equalsIgnoreCase(text(body.get("preferred_yn")))) mapper.clearPreferredProduct(body);
        return changed(mapper.updateAccountProduct(body), body.get("account_ingredient_product_id"));
    }

    public Map<String, Object> deleteAccountProduct(Map<String, Object> body) {
        require(body, "account_ingredient_product_id");
        return changed(mapper.deleteAccountProduct(body), body.get("account_ingredient_product_id"));
    }

    private void require(Map<String, Object> body, String... fields) {
        for (String field : fields) if (text(body.get(field)).isBlank())
            throw new IllegalArgumentException(field + " is required.");
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }
    private Map<String, Object> result(Object id) { return Map.of("code", 200, "message", "success", "id", id); }
    private Map<String, Object> changed(int count, Object id) {
        if (count == 0) throw new IllegalArgumentException("Target row was not found.");
        return result(id);
    }
}
