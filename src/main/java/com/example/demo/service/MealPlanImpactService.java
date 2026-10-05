package com.example.demo.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.ShortageProcurementMapper;

/** Read-only requirement comparison; a reduction is not an automatic order cancellation. */
@Service
public class MealPlanImpactService {
    private final ShortageProcurementService shortages;
    private final ShortageProcurementMapper mapper;
    private final JdbcTemplate jdbc;
    public MealPlanImpactService(ShortageProcurementService shortages,ShortageProcurementMapper mapper,JdbcTemplate jdbc) {
        this.shortages=shortages;this.mapper=mapper;this.jdbc=jdbc;
    }
    public Map<String,Object> compare(Map<String,Object> input) {
        var p=shortages.criteria(input);
        if(!"DRAFT".equals(p.get("source"))) throw new IllegalArgumentException("변경할 식단 초안이 필요합니다.");
        String table=Objects.toString(p.get("table_id"),"");
        List<Map<String,Object>> before=table.isBlank()?List.of():jdbc.queryForList("""
            SELECT r.ingredient_id,COALESCE(i.ingredient_name_std,i.ingredient_name_raw) ingredient_name,
              i.base_unit master_unit,r.base_unit,r.total_required_qty quantity,m.meal_date
            FROM the_full_order.tb_account_meal_ingredient_requirement r
            JOIN the_full_order.tb_account_meal_service m ON m.meal_service_id=r.meal_service_id
            JOIN the_full_order.tb_account_ingredient_master i ON i.account_id=m.account_id AND i.ingredient_id=r.ingredient_id
            WHERE m.account_id=? AND m.table_id=? AND m.status='DRAFT'
            """,p.get("account_id"),table);
        var changes=diff(before,mapper.draftDemand(p));
        var orders=jdbc.queryForList("""
            SELECT p.purchase_order_id,p.requested_delivery_date,p.status,
              COALESCE(NULLIF(i.source_ingredient_id,''),a.mapped_ingredient_id) ingredient_id,
              i.product_name_snapshot product_name,GREATEST(i.order_qty-COALESCE(i.received_order_qty,0),0) remaining_order_qty,
              i.base_qty_per_order_unit,i.base_unit
            FROM the_full_order.tb_account_purchase_order p
            JOIN the_full_order.tb_account_purchase_order_item i ON i.purchase_order_id=p.purchase_order_id
            LEFT JOIN the_full_order.tb_account_ingredient_product a ON a.account_ingredient_product_id=i.account_ingredient_product_id AND a.account_id=p.account_id
            WHERE p.account_id=? AND p.status NOT IN ('CANCELED','FAILED','RECEIVED') AND i.item_status NOT IN ('CANCELED','FAILED','RECEIVED')
            ORDER BY p.requested_delivery_date,p.purchase_order_id LIMIT 1000
            """,p.get("account_id"));
        var ids=new HashSet<String>();for(var c:changes) ids.add(c.get("ingredient_id").toString());
        var affected=orders.stream().filter(o->ids.contains(Objects.toString(o.get("ingredient_id"),""))).toList();
        long completed=table.isBlank()?0:jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_account_meal_service WHERE account_id=? AND table_id=? AND status='COMPLETED'",Long.class,p.get("account_id"),table);
        return Map.of("changes",changes,"affected_orders",affected,"completed_meals",completed,
            "notice","감소량은 이 식단의 필요량 감소입니다. 다른 식단·재고를 확인하고 공급사에 주문 감량·취소 가능 여부를 확인하세요. 주문은 자동 변경되지 않습니다.");
    }
    static List<Map<String,Object>> diff(List<Map<String,Object>> before,List<Map<String,Object>> after) {
        Map<String,Map<String,Object>> rows=new TreeMap<>();
        for(int side=0;side<2;side++) for(var r:side==0?before:after) {
            String id=Objects.toString(r.get("ingredient_id"),""), date=Objects.toString(r.get("meal_date"),"");
            if(id.isBlank() || r.get("quantity")==null) throw new IllegalArgumentException("레시피 수량·식자재를 확인해주세요.");
            String unit=Objects.toString(r.get("master_unit"),"");
            var row=rows.computeIfAbsent(date+"|"+id,k->{Map<String,Object> v=new LinkedHashMap<>();v.put("ingredient_id",id);v.put("ingredient_name",Objects.toString(r.get("ingredient_name"),id));v.put("meal_date",date);v.put("base_unit",unit);v.put("before_qty",BigDecimal.ZERO);v.put("after_qty",BigDecimal.ZERO);return v;});
            String field=side==0?"before_qty":"after_qty";
            var qty=QuantityUnits.convert(new BigDecimal(r.get("quantity").toString()),r.get("base_unit"),row.get("base_unit"));
            row.put(field,((BigDecimal)row.get(field)).add(qty));
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows.values()) {
            var delta=((BigDecimal)row.get("after_qty")).subtract((BigDecimal)row.get("before_qty"));
            if(delta.signum()==0)continue;
            row.put("change_qty",delta);row.put("additional_required_qty",delta.max(BigDecimal.ZERO));row.put("reduced_required_qty",delta.negate().max(BigDecimal.ZERO));result.add(row);
        }
        return result;
    }
}
