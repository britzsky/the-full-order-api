package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 위생 점검 완료(이상 없음) 표시(tb_hygiene_check). 위생 기록 자체는 ERP — 여기는 카테고리·점검일만 */
@Mapper
public interface HygieneCheckMapper {
    /** 기간 안 표시(from·to 둘 다 빈 값이면 전체 기간) — 최신 점검일 먼저 */
    @Select("<script>SELECT hygiene_check_id,account_id,category_key,DATE_FORMAT(check_date,'%Y-%m-%d') check_date,user_id,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_at"
            + " FROM tb_hygiene_check WHERE account_id=#{accountId}"
            + "<if test='from!=null and from!=\"\"'> AND check_date&gt;=#{from}</if><if test='to!=null and to!=\"\"'> AND check_date&lt;=#{to}</if>"
            + " ORDER BY check_date DESC,hygiene_check_id DESC</script>")
    List<Map<String, Object>> list(@Param("accountId") String accountId, @Param("from") String from, @Param("to") String to);

    @Insert("INSERT INTO tb_hygiene_check(account_id,category_key,check_date,user_id) VALUES(#{accountId},#{categoryKey},#{checkDate},#{userId})"
            + " ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)")
    int mark(@Param("accountId") String accountId, @Param("categoryKey") String categoryKey, @Param("checkDate") String checkDate, @Param("userId") String userId);

    /** 표시 취소: 그 카테고리의 기간 안 표시를 모두 지운다(기간이 비면 그 날 하루) */
    @Delete("DELETE FROM tb_hygiene_check WHERE account_id=#{accountId} AND category_key=#{categoryKey} AND check_date BETWEEN #{from} AND #{to}")
    int unmark(@Param("accountId") String accountId, @Param("categoryKey") String categoryKey, @Param("from") String from, @Param("to") String to);
}
