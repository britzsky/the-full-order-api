package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierCatalogMapper {
    List<Map<String, Object>> suppliers(Map<String, Object> params);
    int insertSupplier(Map<String, Object> params);
    int updateSupplier(Map<String, Object> params);
    int deleteSupplier(Map<String, Object> params);

    List<Map<String, Object>> products(Map<String, Object> params);
    int insertProduct(Map<String, Object> params);
    int updateProduct(Map<String, Object> params);
    int deleteProduct(Map<String, Object> params);

    List<Map<String, Object>> prices(Map<String, Object> params);
    int closeCurrentPrice(Map<String, Object> params);
    int insertPrice(Map<String, Object> params);
    int updatePrice(Map<String, Object> params);
    int deletePrice(Map<String, Object> params);

    List<Map<String, Object>> accountProducts(Map<String, Object> params);
    int clearPreferredProduct(Map<String, Object> params);
    int insertAccountProduct(Map<String, Object> params);
    int updateAccountProduct(Map<String, Object> params);
    int deleteAccountProduct(Map<String, Object> params);
}
