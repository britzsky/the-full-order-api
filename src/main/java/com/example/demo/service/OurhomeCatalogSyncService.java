package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Pattern;
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
            // 빠른 경로: 납품일마다 바뀌는 값(가격·판매상태)을 뺀 내용이 같으면 상품·판매조건 갱신을 건너뛰고,
            // 가격·판매상태는 500건씩 묶어 저장한다(상품 1만여 건 × 8쿼리 → 수십 쿼리).
            Map<String,Map<String,Object>> existing=new HashMap<>();
            for(var row:mapper.ourhomeSiteOffers(s)) existing.put(text(row.get("supplier_item_code")),row);
            List<Object> unchanged=new ArrayList<>(); List<Map<String,Object>> prices=new ArrayList<>(), availability=new ArrayList<>();
            int changed=0;
            for(var row:products.values()) {
                Map<String,Object> previous=existing.get(row.get("goodcd"));
                Object offerId;
                if(previous!=null && sameStaticContent(previous,row,deadline(row))) { offerId=previous.get("supplier_offer_id"); unchanged.add(offerId); }
                else { offerId=save(s,row,date); changed++; }
                prices.add(priceRow(offerId,row,date)); availability.add(availabilityRow(offerId,row,date));
            }
            for(int i=0;i<unchanged.size();i+=BATCH) {
                Map<String,Object> touch=new HashMap<>(s); touch.put("provider_revision",date.toString());
                touch.put("offer_ids",unchanged.subList(i,Math.min(i+BATCH,unchanged.size()))); mapper.touchOffers(touch);
            }
            for(int i=0;i<prices.size();i+=BATCH) {
                mapper.insertOfferPrices(Map.of("rows",prices.subList(i,Math.min(i+BATCH,prices.size()))));
                mapper.upsertAvailabilities(Map.of("rows",availability.subList(i,Math.min(i+BATCH,availability.size()))));
            }
            return Map.of("message","아워홈 사업장 연결 및 상품 저장 완료", "products",products.size(),"changed",changed,"siteCode",code,"deliveryDate",date.toString());
        });
    }
    record Deadline(int days, LocalTime time) {}
    private static final Pattern ORDER_INFO=Pattern.compile("^\\s*D-(\\d{1,2})\\s*(?:/\\s*(\\d{1,2}):(\\d{2}))?\\s*$");

    /**
     * 아워홈 연동 가이드(전체 판가 조회): orderdtInfo=마감정보("D-2", "D-2 / 15:00"), ordInfo=주문정보(1:D-1, 2:D-2, 3:D-3이상),
     * ordTime=마감시간(HHMM). 납품일 N일 전 ordTime까지 주문. 일수는 orderdtInfo의 D-N(정확한 일수)과 ordInfo가 맞을 때만,
     * orderdtInfo에 시각이 있으면 ordTime과 같을 때만 확정한다. 어긋나거나 빠지면 null(발주 화면에서 확인 필요).
     */
    static Deadline deadline(Map<String,String> row) {
        var info=ORDER_INFO.matcher(Objects.toString(row.get("orderdtInfo"),""));
        String time=Objects.toString(row.get("ordTime"),"").trim(), ord=Objects.toString(row.get("ordInfo"),"").trim();
        if(!info.matches() || !time.matches("\\d{4}") || !ord.matches("\\d+")) return null;
        int days=Integer.parseInt(info.group(1)), code=Integer.parseInt(ord);
        if(days<1 || (days<3 ? code!=days : code<3)) return null;
        LocalTime cutoff;
        try { cutoff=LocalTime.of(Integer.parseInt(time.substring(0,2)),Integer.parseInt(time.substring(2))); }
        catch(RuntimeException e) { return null; }
        if(info.group(2)!=null && !LocalTime.of(Integer.parseInt(info.group(2)),Integer.parseInt(info.group(3))).equals(cutoff)) return null;
        return new Deadline(days,cutoff);
    }

    static final int BATCH=500;
    /** 납품일마다 바뀌는 값. 변경 판단에서 빼고 가격·판매상태 행에만 저장한다. */
    static final Set<String> DAILY_FIELDS=Set.of("salsUcost","salsUcostKg","SALS_UCOST_KG","goodStatus","goodStatusNm");

    /** 기존 판매조건의 원문(납품일 값 제외)과 마감 규칙이 이번 응답과 같으면 상품·판매조건을 다시 쓰지 않아도 된다. */
    boolean sameStaticContent(Map<String,Object> previous,Map<String,String> row,Deadline deadline) {
        try {
            int lead=Integer.parseInt(text(previous.get("lead_time_days")).isEmpty()?"0":text(previous.get("lead_time_days")));
            if(lead!=(deadline==null?0:deadline.days())) return false;
            String storedTime=text(previous.get("cutoff_time"));
            LocalTime stored=storedTime.isEmpty()?null:LocalTime.parse(storedTime);
            if(!Objects.equals(stored,deadline==null?null:deadline.time())) return false;
            // 원문이 같아도 규격 해석 규칙이 바뀌었으면(예: 용량 ml 추가) 상품을 다시 저장한다.
            var packs=QuantityUnits.ourhomePacks(text(row.get("odrUnit")),text(row.get("goodsz")),text(row.get("odrConvqty")));
            var alt=packs.size()>1?packs.get(1):null;
            String storedAlt=text(previous.get("alt_base_qty"));
            if(alt==null ? !storedAlt.isEmpty()
                : storedAlt.isEmpty() || new BigDecimal(storedAlt).compareTo(alt.quantity())!=0 || !alt.baseUnit().equalsIgnoreCase(text(previous.get("alt_base_unit"))))
                return false;
            Map<String,String> before=new HashMap<>();
            json.readTree(text(previous.get("provider_attributes"))).fields().forEachRemaining(e->before.put(e.getKey(),e.getValue().asText()));
            Map<String,String> now=new HashMap<>(row);
            DAILY_FIELDS.forEach(key->{ before.remove(key); now.remove(key); });
            return before.equals(now);
        } catch(Exception e) { return false; }
    }
    Map<String,Object> priceRow(Object offerId,Map<String,String> row,LocalDate date) {
        Map<String,Object> r=new HashMap<>();
        r.put("supplier_offer_id",offerId); r.put("price_context_value",date.toString()); r.put("purchase_price",number(row.get("salsUcost")));
        r.put("tax_type","Y".equals(row.get("taxYn"))?"Full tax":"N".equals(row.get("taxYn"))?"No tax":"UNKNOWN");
        r.put("effective_from",date.atStartOfDay()); r.put("effective_to",date.plusDays(1).atStartOfDay());
        Map<String,String> daily=new LinkedHashMap<>();
        for(String key:List.of("salsUcost","salsUcostKg")) if(row.get(key)!=null) daily.put(key,row.get(key));
        r.put("price_provider_attributes",json.valueToTree(daily).toString());
        return r;
    }
    Map<String,Object> availabilityRow(Object offerId,Map<String,String> row,LocalDate date) {
        Map<String,Object> r=new HashMap<>();
        r.put("supplier_offer_id",offerId); r.put("delivery_date",date.toString()); r.put("orderable_yn",orderable(row)?"Y":"N");
        r.put("availability_status_code",row.get("goodStatus")); r.put("availability_status_reason",row.get("goodStatusNm"));
        Map<String,String> daily=new LinkedHashMap<>();
        for(String key:List.of("goodStatus","goodStatusNm")) if(row.get(key)!=null) daily.put(key,row.get(key));
        r.put("availability_provider_attributes",json.valueToTree(daily).toString());
        return r;
    }

    /** 새 상품·내용이 바뀐 상품: 상품·규격·거래처 사용상품·판매조건을 저장하고 판매조건 ID를 돌려준다(가격·판매상태는 호출자가 묶어 저장). */
    private Object save(Map<String,Object> site, Map<String,String> row, LocalDate date) {
        Map<String,Object> p=new LinkedHashMap<>(site);
        String unit=text(row.get("odrUnit"));
        var packs=QuantityUnits.ourhomePacks(unit,text(row.get("goodsz")),text(row.get("odrConvqty")));
        var pack=packs.isEmpty()?new QuantityUnits.Pack(BigDecimal.ZERO,QuantityUnits.unit(unit)):packs.get(0);
        BigDecimal step=step(row);
        p.put("supplier_item_code",row.get("goodcd")); p.put("product_name",row.get("goodnm"));
        p.put("ingredient_id",null); p.put("order_unit",unit); p.put("package_qty",pack.quantity());
        p.put("package_unit",pack.baseUnit()); p.put("base_qty",pack.quantity()); p.put("base_unit",pack.baseUnit());
        p.put("alt_base_qty",packs.size()>1?packs.get(1).quantity():null); p.put("alt_base_unit",packs.size()>1?packs.get(1).baseUnit():null);
        Deadline deadline=deadline(row);
        p.put("minimum_order_qty",step); p.put("order_increment_qty",step); p.put("lead_time_days",deadline==null?0:deadline.days());
        p.put("tax_type","Y".equals(row.get("taxYn"))?"Full tax":"N".equals(row.get("taxYn"))?"No tax":"UNKNOWN");
        p.putAll(OurhomeProductImages.from(row));
        mapper.upsertSupplierProduct(p); p.put("supplier_product_id",mapper.supplierProductId(p));
        mapper.refreshUnusedOurhomePack(p);
        packs.stream().filter(option -> option.baseUnit().equals("g")).findFirst().ifPresent(mass -> {
            p.put("confirmed_mass",mass.quantity()); mapper.preserveOurhomeMassOption(p);
        });
        mapper.refreshAccountProductLead(p);
        p.put("delivery_type",row.get("deliveyType")); p.put("cutoff_code",row.get("ordTime"));
        p.put("cutoff_time",deadline==null?null:deadline.time().toString());
        p.put("decimal_order_allowed",row.get("decOdrupYn")); p.put("product_status","ACTIVE");
        p.put("provider_revision",date.toString()); p.put("provider_attributes",json.valueToTree(row).toString());
        mapper.upsertOffer(p);
        return p.get("supplier_offer_id");
    }
    static BigDecimal step(Map<String,String> row) {
        try { BigDecimal n=number(row.get("odrupYnDesc")); return n.signum()>0?n:BigDecimal.ZERO; }
        catch(NumberFormatException e) { return BigDecimal.ZERO; }
    }
    static boolean orderable(Map<String,String> row) {
        return "Q".equals(row.get("goodStatus")) && step(row).signum()>0;
    }
}
