package com.example.demo.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** AI(OpenAI) 호출 이력·토큰 사용량(tb_ai_usage_log). 기록은 INSERT만 한다 */
@Mapper
public interface AiUsageMapper {
    @Insert("INSERT INTO the_full_order.tb_ai_usage_log(feature,account_id,user_id,model,item_count,input_tokens,cached_input_tokens,output_tokens,reasoning_tokens,cost_usd,status,error_message,duration_ms) VALUES(#{feature},#{account_id},#{user_id},#{model},#{item_count},#{input_tokens},#{cached_input_tokens},#{output_tokens},#{reasoning_tokens},#{cost_usd},#{status},#{error_message},#{duration_ms})")
    int insert(Map<String,Object> row);

    /** 오늘(서버 날짜) 전체 예상 비용 — 하루 한도 확인용 */
    @Select("SELECT COALESCE(SUM(cost_usd),0) FROM the_full_order.tb_ai_usage_log WHERE created_at>=CURDATE()")
    BigDecimal todayCost();

    /** 기간 이력(최신순, 최대 500건). account_id가 있으면 그 거래처만 */
    @Select("<script>SELECT l.*,a.account_name FROM the_full_order.tb_ai_usage_log l LEFT JOIN the_full.tb_account a ON a.account_id=l.account_id"
            + " WHERE l.created_at&gt;=#{from} AND l.created_at&lt;DATE_ADD(#{to},INTERVAL 1 DAY)<if test='account_id!=null and account_id!=\"\"'> AND l.account_id=#{account_id}</if>"
            + " ORDER BY l.ai_usage_log_id DESC LIMIT 500</script>")
    List<Map<String,Object>> list(Map<String,Object> p);

    /** 기간 합계(기능별) */
    @Select("<script>SELECT feature,COUNT(*) call_count,SUM(item_count) item_count,SUM(input_tokens) input_tokens,SUM(cached_input_tokens) cached_input_tokens,SUM(output_tokens) output_tokens,SUM(reasoning_tokens) reasoning_tokens,SUM(cost_usd) cost_usd,SUM(status='FAILED') failed_count"
            + " FROM the_full_order.tb_ai_usage_log WHERE created_at&gt;=#{from} AND created_at&lt;DATE_ADD(#{to},INTERVAL 1 DAY)<if test='account_id!=null and account_id!=\"\"'> AND account_id=#{account_id}</if>"
            + " GROUP BY feature ORDER BY feature</script>")
    List<Map<String,Object>> summary(Map<String,Object> p);
}
