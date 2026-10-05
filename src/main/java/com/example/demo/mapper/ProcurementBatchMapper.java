package com.example.demo.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ProcurementBatchMapper {
    @Select("SELECT * FROM the_full_order.tb_procurement_batch WHERE account_id=#{account_id} AND request_key=#{request_key}")
    Map<String,Object> find(Map<String,Object> p);
    @Insert("INSERT INTO the_full_order.tb_procurement_batch(account_id,request_key,request_fingerprint,group_payload) VALUES(#{account_id},#{request_key},#{request_fingerprint},#{group_payload}) ON DUPLICATE KEY UPDATE request_key=request_key")
    int reserve(Map<String,Object> p);
}
