package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.MealUsageMapper;

/** Service-row lock serializes posting, editing and reversal. Every allocation remains auditable. */
@Service
public class MealUsageService {
    private final MealUsageMapper mapper;
    public MealUsageService(MealUsageMapper mapper) { this.mapper=mapper; }

    /** 테스트용: true면 아직 지나지 않은 식사도 사용 확정할 수 있다(차감 방식은 같음). 운영에서는 false. */
    private boolean allowUnserved;
    @org.springframework.beans.factory.annotation.Value("${meal.usage.allow-unserved-confirm:false}")
    void setAllowUnserved(boolean allowUnserved) { this.allowUnserved=allowUnserved; }
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
        if(!allowUnserved) requireServed(s);
        BigDecimal servings=servings(input.getOrDefault("actual_servings",s.get("planned_servings")));
        s.put("user_id",input.get("user_id"));
        consume(s,servings,null,null);
        return Map.of("code",200,"message","사용 확정 및 재고 차감 완료");
    }

    /** 사용 확정·취소된 식사. 그 밖의 상태(DRAFT 등)는 모두 미확정으로 본다(부족량 계산과 같은 기준). */
    private static final Set<String> CLOSED=Set.of("COMPLETED","CANCELED");

    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }

    /** 아직 제공하지 않은 식사는 입고 전 재고를 미리 빼게 되므로 확정하지 않는다. */
    private static void requireServed(Map<String,Object> s) {
        if(LocalDate.parse(s.get("meal_date").toString()).isAfter(today()))
            throw new IllegalArgumentException("아직 지나지 않은 식사는 사용 확정할 수 없습니다: "+label(s));
    }

    private static BigDecimal servings(Object value) {
        BigDecimal servings;
        try { servings=n(value); } catch(NumberFormatException e) { throw new IllegalArgumentException("실제 식수는 1~1000000 사이 정수여야 합니다."); }
        if(servings.signum()<=0 || servings.stripTrailingZeros().scale()>0 || servings.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("실제 식수는 1~1000000 사이 정수여야 합니다.");
        return servings;
    }

    private static String label(Map<String,Object> s) { return s.get("meal_date")+" "+Objects.toString(s.get("meal_slot"),""); }

    /** 식단표(주) 식사와 확정 가능 여부. 수정 저장으로 확정이 풀린 식사는 재확정 필요로 표시한다. */
    public Map<String,Object> week(Map<String,Object> input) {
        if(input.get("account_id")==null || input.get("table_id")==null) throw new IllegalArgumentException("거래처와 식단표가 필요합니다.");
        List<Map<String,Object>> meals=new ArrayList<>();
        for(var meal:mapper.weekMeals(input)) {
            Map<String,Object> row=new LinkedHashMap<>(meal);
            String status=Objects.toString(meal.get("status"),"");
            boolean served=!LocalDate.parse(meal.get("meal_date").toString()).isAfter(today());
            row.put("served",served);
            row.put("confirmable",!CLOSED.contains(status) && (served || allowUnserved));
            meals.add(row);
        }
        return Map.of("meals",meals,"today",today().toString(),"allow_unserved",allowUnserved);
    }

    /**
     * 확정과 같은 차감을 실제로 수행해 본 뒤 전부 롤백한다(유통기한·단위 환산·오래된 재고 우선까지 확정과 동일).
     * 부족해도 멈추지 않고 식사·식재료별 부족량을 모은다.
     */
    @Transactional
    public Map<String,Object> preview(Map<String,Object> input) {
        try {
            var targets=targets(input);
            Map<String,Map<String,Object>> summary=new LinkedHashMap<>();
            List<Map<String,Object>> problems=new ArrayList<>();
            for(var target:targets) {
                Map<String,Object> s=lock(target);
                s.put("user_id",input.get("user_id"));
                List<Map<String,Object>> shortfalls=new ArrayList<>();
                try { consume(s,n(target.get("actual_servings")),summary,shortfalls); }
                catch(IllegalArgumentException e) { problems.add(Map.of("meal",label(s),"message",e.getMessage())); }
                for(var shortfall:shortfalls) problems.add(shortfall);
            }
            List<Map<String,Object>> ingredients=new ArrayList<>(summary.values());
            for(var row:ingredients) row.put("stock_after",n(row.get("stock_before")).subtract(n(row.get("required_qty"))).add(n(row.get("shortage_qty"))));
            String last=targets.stream().map(t->t.get("meal_date").toString()).max(String::compareTo).orElse(today().toString());
            Map<String,Object> q=new HashMap<>(input); q.put("date_to",last);
            var orders=mapper.unreceivedOrders(q);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("meals",targets); result.put("ingredients",ingredients); result.put("problems",problems);
            result.put("unreceived_orders",orders); result.put("can_confirm",!targets.isEmpty() && problems.isEmpty());
            return result;
        } finally {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /** 주 단위 일괄 확정. 한 식사라도 실패하면 전체를 되돌린다(일부만 차감된 상태를 남기지 않음). */
    @Transactional
    public Map<String,Object> completeWeek(Map<String,Object> input) {
        var targets=targets(input);
        if(targets.isEmpty()) throw new IllegalArgumentException("사용 확정할 식사가 없습니다.");
        for(var target:targets) {
            Map<String,Object> s=lock(target);
            if(CLOSED.contains(Objects.toString(s.get("status"),""))) throw new IllegalArgumentException("다른 사용자가 먼저 처리한 식사가 있습니다. 다시 조회해주세요: "+label(s));
            s.put("user_id",input.get("user_id"));
            try { consume(s,n(target.get("actual_servings")),null,null); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException(label(s)+": "+e.getMessage()); }
        }
        return Map.of("code",200,"message","주간 사용 확정 및 재고 차감 완료","completed_count",targets.size());
    }

    /** 요청한 식사가 이 식단표의 확정 가능한 식사인지 확인하고 실제 식수를 붙인다. 식사 목록이 없으면 확정 가능한 식사 전체. */
    private List<Map<String,Object>> targets(Map<String,Object> input) {
        Map<String,Map<String,Object>> confirmable=new LinkedHashMap<>();
        for(var meal:(List<?>)week(input).get("meals")) {
            var row=(Map<?,?>)meal;
            if(Boolean.TRUE.equals(row.get("confirmable"))) {
                Map<String,Object> target=new LinkedHashMap<>();
                row.forEach((k,v)->target.put(k.toString(),v));
                target.put("account_id",input.get("account_id"));
                target.put("actual_servings",servings(target.get("planned_servings")));
                confirmable.put(target.get("meal_service_id").toString(),target);
            }
        }
        if(!(input.get("meals") instanceof List<?> requested)) return new ArrayList<>(confirmable.values());
        List<Map<String,Object>> targets=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Object item:requested) {
            if(!(item instanceof Map<?,?> meal)) throw new IllegalArgumentException("식사 형식을 확인해주세요.");
            String id=Objects.toString(meal.get("meal_service_id"),"");
            Map<String,Object> target=confirmable.get(id);
            if(target==null || !seen.add(id)) throw new IllegalArgumentException("확정할 수 없는 식사입니다. 다시 조회해주세요: "+id);
            if(meal.get("actual_servings")!=null) target.put("actual_servings",servings(meal.get("actual_servings")));
            targets.add(target);
        }
        return targets;
    }

    /**
     * 식사 하나의 재고 차감. shortfalls가 null이면 부족 시 예외(확정), 아니면 부족분을 모으고 있는 만큼만 차감(미리보기).
     * summary는 식재료별 필요량·차감 전 재고·부족량 집계(미리보기용).
     */
    private void consume(Map<String,Object> s,BigDecimal servings,Map<String,Map<String,Object>> summary,List<Map<String,Object>> shortfalls) {
        List<Map<String,Object>> requirements=mapper.requirements(s);
        if(requirements.isEmpty()) throw new IllegalArgumentException("식단 필요량을 먼저 계산해주세요.");
        for(var requirement:requirements) {
            Map<String,Object> p=new HashMap<>(s); p.putAll(requirement);
            BigDecimal remaining=n(requirement.get("qty_per_person")).multiply(servings);
            if(remaining.signum()<=0) throw new IllegalArgumentException("식재료 사용량을 확인해주세요.");
            List<StockPortion> portions = new ArrayList<>();
            // Lock balances in stable order, then consume lots across products by receipt date.
            for(var balance:mapper.balances(p)) {
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
                if(openingBalance.signum()>0) portions.add(new StockPortion(allocation, null, openingBalance, "", "원산지 미확인 (기존 재고)", null));
                for(var lot:usableLots) portions.add(new StockPortion(allocation, lot.get("inventory_lot_id"), n(lot.get("remaining_base_qty")),
                    Objects.toString(lot.get("received_date"), ""), Objects.toString(lot.get("origin_name_snapshot"), "원산지 미확인"),
                    lot.get("product_name_snapshot")));
            }
            portions.sort(Comparator.comparing((StockPortion portion) -> portion.lot() != null)
                .thenComparing(StockPortion::receivedDate).thenComparing(portion -> n(portion.lot())));
            if(summary!=null) {
                String ingredient=requirement.get("ingredient_id").toString();
                Map<String,Object> row=summary.get(ingredient);
                if(row==null) {
                    // 첫 등장 시점의 사용 가능 재고(앞 식사 차감 전)를 차감 전 재고로 기록한다.
                    BigDecimal before=BigDecimal.ZERO;
                    for(var portion:portions) {
                        try { before=before.add(QuantityUnits.convert(portion.quantity(),portion.allocation().get("base_unit"),requirement.get("base_unit"))); }
                        catch(IllegalArgumentException incompatible) { }
                    }
                    row=new LinkedHashMap<>();
                    row.put("ingredient_id",ingredient); row.put("ingredient_name",Objects.toString(requirement.get("ingredient_name"),ingredient));
                    row.put("base_unit",requirement.get("base_unit")); row.put("stock_before",before);
                    row.put("required_qty",BigDecimal.ZERO); row.put("shortage_qty",BigDecimal.ZERO);
                    summary.put(ingredient,row);
                }
                row.put("required_qty",n(row.get("required_qty")).add(sameUnit(remaining,requirement.get("base_unit"),row.get("base_unit"))));
            }
            for(var portion:portions) {
                if(remaining.signum()<=0) break;
                var allocation = portion.allocation();
                BigDecimal available;
                try { available=QuantityUnits.convert(portion.quantity(),allocation.get("base_unit"),requirement.get("base_unit")); }
                catch(IllegalArgumentException incompatible) { continue; }
                if(available.signum()<=0) continue;
                BigDecimal take=remaining.min(available);
                BigDecimal stockQty;
                try { stockQty=QuantityUnits.convert(take,requirement.get("base_unit"),allocation.get("base_unit")).setScale(3,RoundingMode.UNNECESSARY); }
                catch(ArithmeticException e) { throw new IllegalArgumentException("재고 수량 단위의 정밀도를 확인해주세요."); }
                if(stockQty.signum()<=0) throw new IllegalArgumentException("재고 최소 단위보다 작은 사용량입니다.");
                post(allocation,portion.lot(),stockQty,portion.origin(),portion.productName());
                remaining=remaining.subtract(take);
            }
            if(remaining.signum()>0) {
                String name=Objects.toString(requirement.get("ingredient_name"),requirement.get("ingredient_id").toString());
                if(shortfalls==null) throw new IllegalArgumentException("재고가 부족합니다: "+name+" / "+remaining.stripTrailingZeros().toPlainString()+" "+requirement.get("base_unit"));
                Map<String,Object> row=summary==null ? null : summary.get(requirement.get("ingredient_id").toString());
                if(row!=null) row.put("shortage_qty",n(row.get("shortage_qty")).add(sameUnit(remaining,requirement.get("base_unit"),row.get("base_unit"))));
                shortfalls.add(Map.of("meal",label(s),"ingredient_id",requirement.get("ingredient_id"),"ingredient_name",name,
                    "shortage_qty",remaining,"base_unit",requirement.get("base_unit"),"message","재고 부족: "+name+" "+remaining.stripTrailingZeros().toPlainString()+" "+requirement.get("base_unit")));
            }
        }
        s.put("status","COMPLETED"); s.put("actual_servings",servings); mapper.status(s);
    }

    private static BigDecimal sameUnit(BigDecimal quantity,Object from,Object to) {
        try { return QuantityUnits.convert(quantity,from,to); } catch(IllegalArgumentException e) { return quantity; }
    }

    /** 차감할 재고 조각: LOT 없는 기존 잔액 또는 입고 LOT(어느 공급사 상품이었는지 productName으로 남긴다). */
    private record StockPortion(Map<String,Object> allocation, Object lot, BigDecimal quantity, String receivedDate, String origin, Object productName) { }

    private void post(Map<String,Object> source,Object lot,BigDecimal quantity,String origin,Object productName) {
        Map<String,Object> p=new HashMap<>(source);
        p.put("inventory_lot_id",lot); p.put("quantity",quantity); p.put("origin_name_snapshot",origin);
        p.put("product_name_snapshot",productName);
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
