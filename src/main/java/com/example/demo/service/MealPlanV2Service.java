package com.example.demo.service;
import java.util.List; import java.util.Map; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import com.example.demo.mapper.MealPlanV2Mapper;
@Service public class MealPlanV2Service {
 private final MealPlanV2Mapper m; public MealPlanV2Service(MealPlanV2Mapper m){this.m=m;}
 public List<Map<String,Object>> plans(Map<String,Object>p){return m.plans(p);} public List<Map<String,Object>> services(Map<String,Object>p){return m.services(p);} public List<Map<String,Object>> details(Map<String,Object>p){return m.details(p);} public List<Map<String,Object>> costs(Map<String,Object>p){return m.costs(p);} public List<Map<String,Object>> requirements(Map<String,Object>p){return m.requirements(p);}
 public Map<String,Object> createPlan(Map<String,Object>b){req(b,"table_id","account_id","table_year","table_month","table_week");validateMealPlanType(b);m.insertPlan(b);return ok(b.get("table_id"));}
 public Map<String,Object> updatePlan(Map<String,Object>b){req(b,"table_id");validateMealPlanType(b);return changed(m.updatePlan(b),b.get("table_id"));}
 public Map<String,Object> deletePlan(Map<String,Object>b){req(b,"table_id"); if(!m.services(b).isEmpty())throw new IllegalArgumentException("Delete meal services first.");return changed(m.deletePlan(b),b.get("table_id"));}
 public Map<String,Object> createService(Map<String,Object>b){req(b,"table_id","account_id","meal_date","meal_slot","planned_servings","meal_budget_per_person");m.insertService(b);return ok(b.get("meal_service_id"));}
 public Map<String,Object> updateService(Map<String,Object>b){req(b,"meal_service_id");return changed(m.updateService(b),b.get("meal_service_id"));}
 public Map<String,Object> deleteService(Map<String,Object>b){req(b,"meal_service_id"); if(!m.details(b).isEmpty())throw new IllegalArgumentException("Delete meal details first.");return changed(m.deleteService(b),b.get("meal_service_id"));}
 public Map<String,Object> createDetail(Map<String,Object>b){req(b,"meal_service_id","table_id","account_id","menu_id","menu_name");if(m.countCompatibleMenu(b)==0)throw new IllegalArgumentException("The menu meal_plan_type does not match the meal plan.");m.insertDetail(b);return ok(b.get("meal_detail_id"));}
 public Map<String,Object> updateDetail(Map<String,Object>b){req(b,"meal_detail_id");if(b.get("menu_id")!=null){req(b,"table_id","account_id");if(m.countCompatibleMenu(b)==0)throw new IllegalArgumentException("The menu meal_plan_type does not match the meal plan.");}return changed(m.updateDetail(b),b.get("meal_detail_id"));}
 public Map<String,Object> deleteDetail(Map<String,Object>b){req(b,"meal_detail_id");return changed(m.deleteDetail(b),b.get("meal_detail_id"));}
 @Transactional public Map<String,Object> recalculate(Map<String,Object>b){req(b,"meal_service_id","account_id");m.deleteRequirements(b);m.deleteCosts(b);int requirements=m.insertRequirements(b);int costs=m.insertCosts(b);return Map.of("code",200,"message","success","requirement_count",requirements,"menu_cost_count",costs);}
 private void req(Map<String,Object>b,String...fs){for(String f:fs)if(b.get(f)==null||b.get(f).toString().isBlank())throw new IllegalArgumentException(f+" is required.");}
 private void validateMealPlanType(Map<String,Object>b){Object value=b.get("meal_plan_type");if(value==null||value.toString().isBlank())return;int type=Integer.parseInt(value.toString());if(type<0||type>5)throw new IllegalArgumentException("meal_plan_type must be between 0 and 5.");}
 private Map<String,Object>ok(Object id){return Map.of("code",200,"message","success","id",id);} private Map<String,Object>changed(int n,Object id){if(n==0)throw new IllegalArgumentException("Target row was not found.");return ok(id);}
}
