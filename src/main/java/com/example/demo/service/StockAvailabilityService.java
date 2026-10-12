package com.example.demo.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/** All screens use the procurement calculation, including incoming stock and unit conversion. */
@Service
public class StockAvailabilityService {
    private final ShortageProcurementService shortages;
    public StockAvailabilityService(ShortageProcurementService shortages) { this.shortages=shortages; }
    @SuppressWarnings("unchecked")
    public void enrich(List<Map<String,Object>> rows,Map<String,Object> input,boolean menu) {
        Map<String,Object> p=new HashMap<>(input);
        p.remove("sold_to"); p.put("include_sufficient",true);
        if(menu) {
            p.put("source","DRAFT");
            p.put("draft_meals",List.of(Map.of("menu_id",input.get("menu_id"),"planned_servings",input.getOrDefault("servingQty",1))));
        } else p.putIfAbsent("source","INVENTORY");
        var result=shortages.shortages(p);
        var calculated=(List<Map<String,Object>>)result.get("ingredients");
        Map<String,Map<String,Object>> byId=new HashMap<>();
        calculated.forEach(r->byId.put(String.valueOf(r.get("ingredient_id")),r));
        for(var row:rows) {
            var stock=byId.get(String.valueOf(row.get("ingredient_id")));
            if(stock==null) continue;
            Object unit=row.get("base_unit");
            for(String key:List.of("required_qty","safe_stock_qty","incoming_qty","shortage_qty","unconfirmed_usage_qty")) {
                Object value=stock.get(key);
                if(value==null) { row.put(key,"unconfirmed_usage_qty".equals(key) ? BigDecimal.ZERO : null); continue; }
                try { row.put(key,QuantityUnits.convert(new BigDecimal(value.toString()),stock.get("base_unit"),unit)); }
                catch(IllegalArgumentException e) { row.put(key,null); row.put("stock_status","UNKNOWN"); }
            }
            row.put("order_needed_qty",row.get("shortage_qty"));
			if(!menu) row.put("average_usage_qty", row.get("required_qty"));
            row.put("safe_stock_base_qty",row.get("safe_stock_qty"));
            row.put("stock_issues",stock.get("issues"));
            row.put("shortage_scope","INGREDIENT");
            try {
                var current=QuantityUnits.convert(new BigDecimal(stock.get("current_qty").toString()),stock.get("base_unit"),unit);
                row.put("ingredient_current_qty",current);
                if(menu) row.put("current_qty",current);
                boolean shortage=row.get("shortage_qty")!=null && new BigDecimal(row.get("shortage_qty").toString()).signum()>0;
                // 주 단위 사용 확정 전 지난 식사 사용량을 뺀 사용 가능 재고로 위험도를 판단한다.
                var available=current.subtract(row.get("unconfirmed_usage_qty")==null ? BigDecimal.ZERO : new BigDecimal(row.get("unconfirmed_usage_qty").toString()));
                row.put("available_qty",available);
                row.put("stock_status",!((Collection<?>)stock.get("issues")).isEmpty() ? "UNKNOWN" : shortage ? (available.signum()<=0 ? "RED" : "ORANGE") : "GREEN");
            } catch(IllegalArgumentException e) { row.put("stock_status","UNKNOWN"); }
        }
    }
}
