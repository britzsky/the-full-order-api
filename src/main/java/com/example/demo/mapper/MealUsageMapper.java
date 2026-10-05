package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MealUsageMapper {
    Map<String,Object> lockService(Map<String,Object> p);
    List<Map<String,Object>> requirements(Map<String,Object> p);
    List<Map<String,Object>> balances(Map<String,Object> p);
    List<Map<String,Object>> lots(Map<String,Object> p);
    List<Map<String,Object>> activeUsage(Map<String,Object> p);
    Map<String,Object> lockBalance(Map<String,Object> p);
    int changeBalance(Map<String,Object> p);
    int changeLot(Map<String,Object> p);
    int insertUsage(Map<String,Object> p);
    int reverseUsage(Map<String,Object> p);
    int movement(Map<String,Object> p);
    int status(Map<String,Object> p);
    int snapshotOrigins(Map<String,Object> p);
    List<Map<String,Object>> origins(Map<String,Object> p);
}
