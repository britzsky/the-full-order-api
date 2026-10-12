package com.example.demo.service;

import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.example.demo.mapper.MenuMapper;

/**
 * 메뉴 편성(예상 원가) 단가. 기본은 자동 단가(판가→최근 입고→재고 평균, MenuMapper.accountProductPlanningPrices),
 * 사용자가 직접 입력한 단가(MANUAL)가 있으면 그 값을 쓴다. AUTO 행은 직접 입력을 해제하고 자동 단가로 되돌린 기록.
 */
@Service
public class PlanningCostService {
    private final JdbcTemplate jdbc;
    private final MenuMapper menus;
    public PlanningCostService(JdbcTemplate jdbc, MenuMapper menus) { this.jdbc=jdbc; this.menus=menus; }
    private static String text(Object x) { return x==null?"":x.toString().trim(); }
    private static BigDecimal decimal(Object x) { return x==null||text(x).isEmpty()?BigDecimal.ZERO:new BigDecimal(x.toString()); }

    private Map<String,Object> price(String account,String ingredient,long product) {
        var p=new HashMap<String,Object>();
        p.put("account_id",account);p.put("ingredient_id",ingredient);p.put("supplier_product_id",product);
        var row=menus.productPlanningPrice(p);
        if(row==null) throw new IllegalArgumentException("식재료에 연결된 사용상품을 먼저 확인해주세요.");
        return row;
    }

    public Map<String,Object> preview(String account,String ingredient,long product) {
        var row=price(account,ingredient,product);
        var result=new LinkedHashMap<String,Object>();
        result.put("base_unit",row.get("base_unit"));
        result.put("auto",row.get("auto_unit_cost")==null?null:Map.of("source_type",row.get("auto_source"),"unit_cost",row.get("auto_unit_cost"),"source_date",text(row.get("auto_source_date"))));
        result.put("manual",row.get("manual_cost")==null?null:Map.of("unit_cost",row.get("manual_cost"),"source_date",text(row.get("manual_date"))));
        result.put("preferred","1".equals(text(row.get("choice_rank"))));
        var history=jdbc.queryForList("SELECT planning_cost_id,unit_cost,base_unit,source_type,confirmed_at,user_id FROM the_full_order.tb_account_planning_cost WHERE account_id=? AND ingredient_id=? AND supplier_product_id=? ORDER BY planning_cost_id DESC LIMIT 10",account,ingredient,product);
        result.put("expected_version",history.isEmpty()?0:history.get(0).get("planning_cost_id"));
        result.put("history",history);
        return result;
    }

    @Transactional
    public Map<String,Object> confirm(Map<String,Object> b) {
        String account=text(b.get("account_id")),ingredient=text(b.get("ingredient_id")),source=text(b.get("source_type"));
        long product=decimal(b.get("supplier_product_id")).longValueExact();
        var link=jdbc.queryForList("SELECT account_ingredient_product_id FROM the_full_order.tb_account_ingredient_product WHERE account_id=? AND mapped_ingredient_id=? AND supplier_product_id=? AND active_yn='Y' FOR UPDATE",account,ingredient,product);
        if(link.isEmpty()) throw new IllegalArgumentException("식재료에 연결된 사용상품을 먼저 확인해주세요.");
        var current=preview(account,ingredient,product);
        if(b.containsKey("expected_version")&&decimal(current.get("expected_version")).compareTo(decimal(b.get("expected_version")))!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"단가가 다른 곳에서 변경되었습니다. 다시 조회해주세요.");
        String unit=text(current.get("base_unit"));
        if("AUTO".equals(source)) {
            if(current.get("manual")==null) return current;
            jdbc.update("INSERT INTO the_full_order.tb_account_planning_cost(account_id,ingredient_id,supplier_product_id,unit_cost,base_unit,source_type,user_id) VALUES(?,?,?,NULL,?,'AUTO',?)",account,ingredient,product,unit,b.get("user_id"));
            return preview(account,ingredient,product);
        }
        if(!"MANUAL".equals(source)) throw new IllegalArgumentException("단가 구분을 확인해주세요.");
        if(!QuantityUnits.unit(unit).equals(QuantityUnits.unit(b.get("base_unit")))) throw new IllegalArgumentException("기준단위가 변경되었습니다. 다시 조회해주세요.");
        BigDecimal cost;
        try { cost=decimal(b.get("unit_cost")).stripTrailingZeros(); } catch(NumberFormatException e) { cost=BigDecimal.ZERO; }
        if(cost.signum()<=0||cost.scale()>6||cost.compareTo(new BigDecimal("999999999999.999999"))>0)
            throw new IllegalArgumentException("단가는 0보다 큰 금액(소수점 6자리 이하)이어야 합니다.");
        jdbc.update("INSERT INTO the_full_order.tb_account_planning_cost(account_id,ingredient_id,supplier_product_id,unit_cost,base_unit,source_type,user_id) VALUES(?,?,?,?,?,'MANUAL',?)",account,ingredient,product,cost,unit,b.get("user_id"));
        return preview(account,ingredient,product);
    }
}
