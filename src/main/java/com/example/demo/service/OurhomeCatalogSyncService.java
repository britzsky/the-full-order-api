package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.example.demo.service.OrderWorkflowService.*;

@Service
public class OurhomeCatalogSyncService {
    private final OurhomeSiteService api;
    private final SupplierIntegrationMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final SupplierSiteService sites;
    public OurhomeCatalogSyncService(OurhomeSiteService api, SupplierIntegrationMapper mapper,
            ObjectMapper json, PlatformTransactionManager manager, SupplierSiteService sites) {
        this.api=api; this.mapper=mapper; this.json=json; this.tx=new TransactionTemplate(manager); this.sites=sites;
    }
    /** 아워홈 전체 사업장을 사업장 마스터에만 저장한다. 거래처 연결은 하지 않는다. */
    public Map<String,Object> syncSites(String userId) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var site:api.sites().sites()) rows.add(masterRow(site));
        int saved=sites.saveProviderSites("OURHOME",rows,userId);
        return Map.of("code",200,"received_count",rows.size(),"saved_count",saved);
    }
    private Map<String,Object> masterRow(OurhomeSiteService.Site site) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("external_site_code",site.siteCode()); row.put("external_site_name",site.siteName());
        row.put("representative_site_code",site.representativeCode()); row.put("representative_site_name",site.representativeName());
        row.put("center_code",site.centerCode()); row.put("center_name",site.centerName());
        row.put("price_context_code","DATE"); row.put("provider_attributes",json.valueToTree(site).toString());
        return row;
    }
    public Map<String,Object> sync(Map<String,Object> input) {
        String account=required(input,"account_id"), code=required(input,"siteCode");
        LocalDate date=LocalDate.parse(required(input,"deliveryDate"));
        var site=api.requireSite(code);
        if (!site.deliveryDates().contains(date.toString())) throw new IllegalArgumentException("발주 가능일을 선택하세요.");
        // 전체 페이지가 정상 수집되기 전에는 기존 카탈로그를 수정하지 않는다.
        Map<String,Map<String,String>> products=new LinkedHashMap<>();
        Set<String> cursors=new HashSet<>(); String cursor=""; int pages=0, expected=-1;
        do {
            var page=api.catalogPage(site,date.toString(),cursor);
            if(expected<0) expected=page.totalRow();
            if(expected!=page.totalRow()) throw new IllegalStateException("조회 도중 상품 건수가 변경되었습니다. 다시 동기화하세요.");
            for(var row:page.products()) {
                if(products.putIfAbsent(row.get("goodcd"),row)!=null) throw new IllegalStateException("중복 상품 페이지입니다. 다시 동기화하세요.");
            }
            if (!page.hasNext()) break;
            cursor=page.nextKey();
            if(!cursors.add(cursor) || ++pages>1000) throw new IllegalStateException("상품 페이지 종료를 확인하지 못했습니다.");
        } while(true);
        if(expected!=products.size()) throw new IllegalStateException("전체 상품 수집 건수가 일치하지 않습니다. 다시 동기화하세요.");
        return tx.execute(status -> {
            Map<String,Object> s=new LinkedHashMap<>();
            s.put("supplier_code","OURHOME"); s.put("supplier_name","아워홈"); s.put("user_id",text(input.get("user_id")));
            Long supplierId=sites.supplierId("OURHOME",text(input.get("user_id")));
            s.put("supplier_id",supplierId); s.put("account_id",account); s.put("external_site_code",code);
            s.put("active_yn","Y"); // 아래 상품·판매조건 저장이 이 값을 그대로 쓴다.
            // 거래처에 다른 아워홈 사업장이 지정되어 있으면 저장하지 않는다(사업장 매핑 화면에서 변경).
            sites.saveProviderSite(supplierId,masterRow(site),text(input.get("user_id")));
            s.put("supplier_account_site_id",sites.requireOrAssign(account,"OURHOME",code,text(input.get("user_id"))));
            s.put("delivery_date",date.toString()); mapper.invalidateOurhomeAvailability(s);
            for(var row:products.values()) save(s,row,date);
            return Map.of("message","아워홈 사업장 연결 및 상품 저장 완료", "products",products.size(),"siteCode",code,"deliveryDate",date.toString());
        });
    }
    private void save(Map<String,Object> site, Map<String,String> row, LocalDate date) {
        Map<String,Object> p=new LinkedHashMap<>(site);
        String unit=text(row.get("odrUnit"));
        var packs=QuantityUnits.ourhomePacks(unit,text(row.get("goodsz")),text(row.get("odrConvqty")));
        var pack=packs.isEmpty()?new QuantityUnits.Pack(BigDecimal.ZERO,QuantityUnits.unit(unit)):packs.get(0);
        BigDecimal step=step(row);
        p.put("supplier_item_code",row.get("goodcd")); p.put("product_name",row.get("goodnm"));
        p.put("ingredient_id",null); p.put("order_unit",unit); p.put("package_qty",pack.quantity());
        p.put("package_unit",pack.baseUnit()); p.put("base_qty",pack.quantity()); p.put("base_unit",pack.baseUnit());
        p.put("alt_base_qty",packs.size()>1?packs.get(1).quantity():null); p.put("alt_base_unit",packs.size()>1?packs.get(1).baseUnit():null);
        p.put("minimum_order_qty",step); p.put("order_increment_qty",step); p.put("lead_time_days",0);
        p.put("tax_type","Y".equals(row.get("taxYn"))?"Full tax":"N".equals(row.get("taxYn"))?"No tax":"UNKNOWN");
        p.putAll(OurhomeProductImages.from(row));
        mapper.upsertSupplierProduct(p); p.put("supplier_product_id",mapper.supplierProductId(p)); mapper.upsertAccountProduct(p);
        p.put("delivery_type",row.get("deliveyType")); p.put("cutoff_code",row.get("ordTime"));
        p.put("decimal_order_allowed",row.get("decOdrupYn")); p.put("product_status","ACTIVE");
        p.put("provider_revision",date.toString()); p.put("provider_attributes",json.valueToTree(row).toString());
        mapper.upsertOffer(p);
        p.put("price_context_type","DATE"); p.put("price_context_value",date.toString());
        p.put("purchase_price",number(row.get("salsUcost"))); p.put("currency","KRW");
        p.put("effective_from",date.atStartOfDay()); p.put("effective_to",date.plusDays(1).atStartOfDay());
        p.put("price_provider_attributes",json.valueToTree(row).toString()); mapper.insertOfferPrice(p);
        p.put("delivery_date",date.toString()); p.put("orderable_yn",orderable(row)?"Y":"N");
        p.put("availability_status_code",row.get("goodStatus")); p.put("availability_status_reason",row.get("goodStatusNm"));
        p.put("availability_provider_attributes",json.valueToTree(row).toString()); mapper.upsertAvailability(p);
    }
    static BigDecimal step(Map<String,String> row) {
        try { BigDecimal n=number(row.get("odrupYnDesc")); return n.signum()>0?n:BigDecimal.ZERO; }
        catch(NumberFormatException e) { return BigDecimal.ZERO; }
    }
    static boolean orderable(Map<String,String> row) {
        return "Q".equals(row.get("goodStatus")) && step(row).signum()>0;
    }
}
