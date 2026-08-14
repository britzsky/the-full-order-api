package com.example.demo.mapper;
import java.util.List; import java.util.Map; import org.apache.ibatis.annotations.Mapper;
@Mapper public interface MealPlanV2Mapper {
 List<Map<String,Object>> plans(Map<String,Object> p); int insertPlan(Map<String,Object> p); int updatePlan(Map<String,Object> p); int deletePlan(Map<String,Object> p);
 List<Map<String,Object>> services(Map<String,Object> p); int insertService(Map<String,Object> p); int updateService(Map<String,Object> p); int deleteService(Map<String,Object> p);
 List<Map<String,Object>> details(Map<String,Object> p); int countCompatibleMenu(Map<String,Object> p); int insertDetail(Map<String,Object> p); int updateDetail(Map<String,Object> p); int deleteDetail(Map<String,Object> p);
 List<Map<String,Object>> costs(Map<String,Object> p); List<Map<String,Object>> requirements(Map<String,Object> p);
 int deleteCosts(Map<String,Object> p); int deleteRequirements(Map<String,Object> p); int insertCosts(Map<String,Object> p); int insertRequirements(Map<String,Object> p);
}
