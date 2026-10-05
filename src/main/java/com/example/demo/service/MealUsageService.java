package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.MealUsageMapper;

/** Service-row lock serializes posting, editing and reversal. Every allocation remains auditable. */
@Service
public class MealUsageService {
    private final MealUsageMapper mapper;
    public MealUsageService(MealUsageMapper mapper) { this.mapper=mapper; }
    private static BigDecimal n(Object v) { return v==null ? BigDecimal.ZERO : new BigDecimal(v.toString()); }

    public Map<String,Object> lock(Map<String,Object> p) {
        Map<String,Object> s=mapper.lockService(p);
        if(s==null) throw new IllegalArgumentException("식단을 찾을 수 없습니다.");
        return s;
    }

    @Transactional
    public Map<String,Object> complete(Map<String,Object> input) {
        Map<String,Object> s=lock(input);
        if("COMPLETED".equals(s.get("status"))) return Map.of("code",200,"message","이미 사용 확정되었습니다.");
        if("CANCELED".equals(s.get("status"))) throw new IllegalArgumentException("취소된 식단은 수정 후 사용 확정하세요.");
        BigDecimal servings=n(input.getOrDefault("actual_servings",s.get("planned_servings")));
        if(servings.signum()<=0 || servings.stripTrailingZeros().scale()>0 || servings.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("실제 식수는 1~1000000 사이 정수여야 합니다.");
        s.put("user_id",input.get("user_id"));
        List<Map<String,Object>> requirements=mapper.requirements(s);
        if(requirements.isEmpty()) throw new IllegalArgumentException("식단 필요량을 먼저 계산해주세요.");
        for(var requirement:requirements) {
            Map<String,Object> p=new HashMap<>(s); p.putAll(requirement);
            BigDecimal remaining=n(requirement.get("qty_per_person")).multiply(servings);
            if(remaining.signum()<=0) throw new IllegalArgumentException("식재료 사용량을 확인해주세요.");
            // Locks all matching balances in stable order. Inactive old products remain usable.
            for(var balance:mapper.balances(p)) {
                if(remaining.signum()<=0) break;
                Map<String,Object> allocation=new HashMap<>(p); allocation.putAll(balance);
                allocation.put("menu_id",requirement.get("menu_id"));
                allocation.put("ingredient_id",requirement.get("ingredient_id"));
                List<Map<String,Object>> lots=mapper.lots(allocation);
                BigDecimal lotTotal=lots.stream().map(l->n(l.get("remaining_base_qty"))).reduce(BigDecimal.ZERO,BigDecimal::add);
                if(lotTotal.compareTo(n(balance.get("current_base_qty")))>0)
                    throw new IllegalArgumentException("재고 잔액과 LOT 수량이 다릅니다. 재고 조정 내역을 확인해주세요.");
                BigDecimal openingBalance=n(balance.get("current_base_qty")).subtract(lotTotal);
                List<Map<String,Object>> usableLots=lots.stream().filter(l->"AVAILABLE".equals(l.get("status")))
                    .filter(l->l.get("expiration_date")==null || !java.time.LocalDate.parse(l.get("expiration_date").toString()).isBefore(java.time.LocalDate.parse(s.get("meal_date").toString()))).toList();
                BigDecimal usable=openingBalance.add(usableLots.stream().map(l->n(l.get("remaining_base_qty"))).reduce(BigDecimal.ZERO,BigDecimal::add));
                BigDecimal available;
                try { available=QuantityUnits.convert(usable,balance.get("base_unit"),requirement.get("base_unit")); }
                catch(IllegalArgumentException incompatible) { continue; }
                if(available.signum()<=0) continue;
                BigDecimal take=remaining.min(available);
                BigDecimal stockQty;
                try { stockQty=QuantityUnits.convert(take,requirement.get("base_unit"),balance.get("base_unit")).setScale(3,RoundingMode.UNNECESSARY); }
                catch(ArithmeticException e) { throw new IllegalArgumentException("재고 수량 단위의 정밀도를 확인해주세요."); }
                if(stockQty.signum()<=0) throw new IllegalArgumentException("재고 최소 단위보다 작은 사용량입니다.");
                // Pre-LOT opening inventory is older than tracked receipts; origin is explicitly unknown.
                BigDecimal opening=openingBalance.min(stockQty);
                if(opening.signum()>0) { post(allocation,null,opening,"원산지 미확인 (기존 재고)"); stockQty=stockQty.subtract(opening); }
                for(var lot:usableLots) {
                    if(stockQty.signum()<=0) break;
                    BigDecimal q=stockQty.min(n(lot.get("remaining_base_qty")));
                    post(allocation,lot.get("inventory_lot_id"),q,Objects.toString(lot.get("origin_name_snapshot"),"원산지 미확인"));
                    stockQty=stockQty.subtract(q);
                }
                if(stockQty.signum()>0) throw new IllegalArgumentException("사용 가능한 LOT 재고가 부족합니다.");
                remaining=remaining.subtract(take);
            }
            if(remaining.signum()>0) throw new IllegalArgumentException("재고가 부족합니다: "+requirement.get("ingredient_id")+" / "+remaining+" "+requirement.get("base_unit"));
        }
        s.put("status","COMPLETED"); s.put("actual_servings",servings); mapper.status(s);
        return Map.of("code",200,"message","사용 확정 및 재고 차감 완료");
    }

    private void post(Map<String,Object> source,Object lot,BigDecimal quantity,String origin) {
        Map<String,Object> p=new HashMap<>(source);
        p.put("inventory_lot_id",lot); p.put("quantity",quantity); p.put("origin_name_snapshot",origin);
        p.put("usage_id",UUID.randomUUID().toString());
        p.put("quantity_delta",quantity.negate());
        Map<String,Object> before=mapper.lockBalance(p);
        if(before==null) throw new IllegalArgumentException("원본 재고를 찾을 수 없습니다.");
        p.put("unit_cost_snapshot",n(before.get("average_unit_cost")));
        apply(p,before);
        mapper.insertUsage(p);
    }

    private void apply(Map<String,Object> p,Map<String,Object> before) {
        if(before==null) throw new IllegalArgumentException("원본 재고를 찾을 수 없습니다.");
        p.put("quantity_before",before.get("current_base_qty"));
        p.put("quantity_after",n(before.get("current_base_qty")).add(n(p.get("quantity_delta"))));
        p.put("amount_delta",n(p.get("quantity_delta")).multiply(n(p.get("unit_cost_snapshot"))).setScale(2,RoundingMode.HALF_UP));
        p.put("average_unit_cost_before",n(before.get("average_unit_cost")));
        if(mapper.changeBalance(p)!=1) throw new IllegalArgumentException("재고가 변경되었거나 부족합니다. 다시 조회해주세요.");
        if(p.get("inventory_lot_id")!=null && mapper.changeLot(p)!=1) throw new IllegalArgumentException("LOT 재고를 확인해주세요.");
        p.put("average_unit_cost_after",mapper.lockBalance(p).get("average_unit_cost"));
        p.put("movement_id",UUID.randomUUID().toString());
        p.put("movement_type",n(p.get("quantity_delta")).signum()<0 ? "OUT" : "RETURN");
        mapper.movement(p);
    }

    @Transactional
    public void reopen(Map<String,Object> input) {
        Map<String,Object> s=lock(input);
        for(var row:mapper.activeUsage(s)) {
            Map<String,Object> p=new HashMap<>(row); p.put("user_id",input.get("user_id"));
            p.put("quantity_delta",row.get("quantity"));
            apply(p,mapper.lockBalance(p));
            if(mapper.reverseUsage(p)!=1) throw new IllegalStateException("사용 이력 복원 충돌");
        }
        s.put("user_id",input.get("user_id")); s.put("status","DRAFT"); s.put("actual_servings",null); mapper.status(s);
    }

    @Transactional
    public Map<String,Object> cancel(Map<String,Object> input) {
        reopen(input); Map<String,Object> s=lock(input); s.put("status","CANCELED"); s.put("user_id",input.get("user_id")); mapper.status(s);
        return Map.of("code",200,"message","취소 및 재고 복원 완료");
    }
    public void snapshot(Map<String,Object> p) { mapper.snapshotOrigins(p); }
    public List<Map<String,Object>> origins(Map<String,Object> p) {
        if(p.get("account_id")==null || p.get("table_id")==null) throw new IllegalArgumentException("거래처와 식단표가 필요합니다.");
        return mapper.origins(p);
    }
}
