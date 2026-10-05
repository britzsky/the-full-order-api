package com.example.demo.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.IngredientLinkMapper;
import com.example.demo.mapper.SupplierCatalogMapper;

@Service
public class IngredientLinkService {
    private final IngredientLinkMapper links;
    private final SupplierCatalogMapper catalog;
    public IngredientLinkService(IngredientLinkMapper links, SupplierCatalogMapper catalog) {
        this.links=links; this.catalog=catalog;
    }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private void require(Map<String,Object> b,String... fields) {
        for(String key:fields) if(text(b.get(key)).isEmpty()) throw new IllegalArgumentException(key+" is required.");
    }
    private BigDecimal decimal(Object value) { return new BigDecimal(text(value)); }

    @Transactional
    public Map<String,Object> change(Map<String,Object> body) {
        require(body,"action","account_id","ingredient_id");
        String action=text(body.get("action"));
        if(!java.util.Set.of("LINK","UNLINK","REPLACE").contains(action)) throw new IllegalArgumentException("지원하지 않는 연결 작업입니다.");
        // 기준단위는 거래처 식재료 기준 (본사 식자재 마스터 아님). 행 잠금으로 같은 식재료 동시 연결을 직렬화한다.
        String unit=links.lockAccountIngredientUnit(body);
        if(unit==null) throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다.");
        Map<String,Object> old=null;
        if(!action.equals("LINK")) {
            require(body,"account_ingredient_product_id");
            old=links.lockLink(body);
            if(old==null) throw new IllegalArgumentException("현재 거래처의 연결을 찾을 수 없습니다.");
            if(!text(old.get("mapped_ingredient_id")).equals(text(body.get("ingredient_id")))) throw new IllegalArgumentException("기존 상품의 식재료 연결이 변경되었습니다. 다시 조회해주세요.");
        }
        if(action.equals("UNLINK")) {
            deactivate(body,old);
            return Map.of("code",200,"message","거래처 연결을 해제했습니다.");
        }
        // expected_ingredient_id: 조회 당시 이 거래처에서 상품이 연결돼 있던 식재료. 미연결이면 빈 값.
        require(body,"supplier_product_id","supplier_offer_id","base_qty","base_unit","order_unit");
        Long offerProduct=links.offerProduct(body);
        if(offerProduct==null || !offerProduct.toString().equals(text(body.get("supplier_product_id")))) throw new IllegalArgumentException("현재 거래처에서 조회한 상품을 선택해주세요.");
        if(old!=null && text(old.get("supplier_product_id")).equals(text(body.get("supplier_product_id")))) throw new IllegalArgumentException("교체할 다른 상품을 선택해주세요.");
        Map<String,Object> product=catalog.productForUpdate(body);
        if(product==null || !"Y".equals(text(product.get("active_yn")))) throw new IllegalArgumentException("사용 가능한 상품을 찾을 수 없습니다.");
        Map<String,Object> target=links.lockProductLink(body);
        // 본사 식자재 분류(product.ingredient_id)는 거래처 연결과 무관하다. 이 거래처에서 사용 중인 연결만 비교한다.
        String current=target != null && "Y".equals(text(target.get("active_yn"))) ? text(target.get("mapped_ingredient_id")) : "";
        if(!current.equals(text(body.get("expected_ingredient_id"))) && !current.equals(text(body.get("ingredient_id")))) throw new IllegalArgumentException("상품 연결이 변경되었습니다. 다시 조회해주세요.");
        if(!text(product.get("order_unit")).equals(text(body.get("order_unit")))) throw new IllegalArgumentException("상품 규격이 변경되었습니다. 다시 조회해주세요.");
        // 상품 규격(대표·두 번째) 중 거래처 식재료 기준단위로 환산되는 것을 이 연결의 규격으로 쓴다. 예: 계란 30EA / 1560g
        QuantityUnits.Pack pack=QuantityUnits.packFor(productPacks(product),unit);
        if(pack==null) throw new IllegalArgumentException("상품 규격을 식재료 기준단위("+unit+")로 환산할 수 없습니다. 상품 규격 또는 식재료 기준단위를 확인해주세요.");
        if(pack.quantity().compareTo(decimal(body.get("base_qty")))!=0 || !QuantityUnits.unit(pack.baseUnit()).equals(QuantityUnits.unit(body.get("base_unit")))) throw new IllegalArgumentException("상품 규격이 변경되었습니다. 다시 조회해주세요.");
        for(String stock:links.stockUnits(body))
            if(!QuantityUnits.unit(stock).equals(QuantityUnits.unit(pack.baseUnit()))) throw new IllegalArgumentException("이 상품의 기존 재고가 "+stock+" 단위로 남아 있어 "+pack.baseUnit()+" 규격으로 연결할 수 없습니다. 재고를 정리한 뒤 다시 연결해주세요.");
        body.put("link_base_qty",pack.quantity());body.put("link_base_unit",pack.baseUnit());
        // Keep product units, prices, order history and balances unchanged.
        Map<String,Object> targetPatch=new HashMap<>();
        targetPatch.put("account_id",body.get("account_id"));targetPatch.put("supplier_product_id",body.get("supplier_product_id"));targetPatch.put("active_yn","Y");targetPatch.put("user_id",body.get("user_id"));
        links.clearPreferred(body);
        targetPatch.put("preferred_yn","Y");
        if(target==null) { targetPatch.put("default_location_id","L999");catalog.insertAccountProduct(targetPatch); }
        else { targetPatch.put("account_ingredient_product_id",target.get("account_ingredient_product_id"));catalog.updateAccountProduct(targetPatch); }
        links.setIngredient(body);
        links.ensureInventory(body);
        if(old!=null) deactivate(body,old);
        return Map.of("code",200,"message",old==null?"연결했습니다.":"교체했습니다.");
    }
    private java.util.List<QuantityUnits.Pack> productPacks(Map<String,Object> product) {
        java.util.List<QuantityUnits.Pack> packs=new java.util.ArrayList<>();
        if(!text(product.get("base_qty")).isEmpty()) packs.add(new QuantityUnits.Pack(decimal(product.get("base_qty")),text(product.get("base_unit"))));
        if(!text(product.get("alt_base_qty")).isEmpty()) packs.add(new QuantityUnits.Pack(decimal(product.get("alt_base_qty")),text(product.get("alt_base_unit"))));
        return packs;
    }
    private void deactivate(Map<String,Object> body,Map<String,Object> old) {
        Map<String,Object> patch=new HashMap<>();
        patch.put("account_id",body.get("account_id"));patch.put("account_ingredient_product_id",old.get("account_ingredient_product_id"));
        patch.put("active_yn","N");patch.put("preferred_yn","N");patch.put("user_id",body.get("user_id"));
        catalog.updateAccountProduct(patch);
    }
}
