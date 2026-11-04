package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 더채움 유통 — 본사 협력사 관리(판매 주체·협력사·카테고리·상품·가격·판매 가능). 테이블은 ddl-source 유통 정본(tb_dist_*).
 * 가격 기간은 [시작, 종료) — 현재 적용 = start_at<=NOW() AND (end_at IS NULL OR end_at>NOW()). 매입가는 날짜 단위 [시작일, 종료일].
 * 같은 시각(매입가는 같은 날)에 다시 바꾸면 앞 행은 길이 0(종료=시작, 매입가는 종료=시작 전날)으로 닫혀 이력에만 남는다.
 */
@Mapper
public interface DistAdminMapper {
	/** 가격 기간 비교는 SQL의 NOW()와 같은 시계를 써야 한다(운영 DB 시간대가 앱 서버와 다를 수 있음) */
	@Select("SELECT DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s')")
	String dbNow();

	/* ---------- 판매 주체(더채움 사업자 정보, 지금은 1곳) ---------- */
	@Select("SELECT * FROM the_full_order.tb_dist_seller ORDER BY active_yn DESC,seller_id LIMIT 1")
	Map<String, Object> seller();

	@Insert("""
		INSERT INTO the_full_order.tb_dist_seller(seller_code,seller_name,biz_no,ceo_name,mail_order_reg_no,biz_type,biz_item,phone,email,zipcode,address1,address2,
		       deposit_bank_name,deposit_account_no,deposit_account_holder,deposit_due_days,user_id)
		VALUES(#{seller_code},#{seller_name},#{biz_no},#{ceo_name},#{mail_order_reg_no},#{biz_type},#{biz_item},#{phone},#{email},#{zipcode},#{address1},#{address2},
		       #{deposit_bank_name},#{deposit_account_no},#{deposit_account_holder},#{deposit_due_days},#{user_id})""")
	int insertSeller(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_seller SET seller_name=#{seller_name},biz_no=#{biz_no},ceo_name=#{ceo_name},mail_order_reg_no=#{mail_order_reg_no},
		       biz_type=#{biz_type},biz_item=#{biz_item},phone=#{phone},email=#{email},zipcode=#{zipcode},address1=#{address1},address2=#{address2},
		       deposit_bank_name=#{deposit_bank_name},deposit_account_no=#{deposit_account_no},deposit_account_holder=#{deposit_account_holder},
		       deposit_due_days=#{deposit_due_days},user_id=#{user_id}
		 WHERE seller_id=#{seller_id}""")
	int updateSeller(Map<String, Object> p);

	/* ---------- 협력사 ---------- */
	@Select("""
		SELECT p.partner_id,p.partner_code,p.partner_name,p.partner_type,p.biz_no,p.ceo_name,p.manager_name,p.manager_phone,p.manager_email,
		       p.zipcode,p.address1,p.address2,p.po_send_method,p.po_send_target,p.default_carrier_code,
		       DATE_FORMAT(p.order_cutoff_time,'%H:%i') order_cutoff_time,p.lead_time_days,p.settle_cycle,p.active_yn,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_product d WHERE d.partner_id=p.partner_id) product_count,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_product d WHERE d.partner_id=p.partner_id AND d.sale_status='ON_SALE') on_sale_count
		  FROM the_full_order.tb_dist_partner p
		 ORDER BY p.active_yn DESC,p.partner_name""")
	List<Map<String, Object>> partners();

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_partner WHERE partner_id=#{partner_id}")
	int partnerExists(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_partner WHERE partner_code=#{partner_code} AND partner_id<>#{partner_id}")
	int samePartnerCode(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_partner(partner_code,partner_name,partner_type,biz_no,ceo_name,manager_name,manager_phone,manager_email,
		       zipcode,address1,address2,po_send_method,po_send_target,default_carrier_code,order_cutoff_time,lead_time_days,settle_cycle,active_yn,user_id)
		VALUES(#{partner_code},#{partner_name},#{partner_type},#{biz_no},#{ceo_name},#{manager_name},#{manager_phone},#{manager_email},
		       #{zipcode},#{address1},#{address2},#{po_send_method},#{po_send_target},#{default_carrier_code},#{order_cutoff_time},#{lead_time_days},#{settle_cycle},#{active_yn},#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "partner_id", keyColumn = "partner_id")
	int insertPartner(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_partner SET partner_code=#{partner_code},partner_name=#{partner_name},partner_type=#{partner_type},biz_no=#{biz_no},
		       ceo_name=#{ceo_name},manager_name=#{manager_name},manager_phone=#{manager_phone},manager_email=#{manager_email},zipcode=#{zipcode},
		       address1=#{address1},address2=#{address2},po_send_method=#{po_send_method},po_send_target=#{po_send_target},
		       default_carrier_code=#{default_carrier_code},order_cutoff_time=#{order_cutoff_time},lead_time_days=#{lead_time_days},
		       settle_cycle=#{settle_cycle},active_yn=#{active_yn},user_id=#{user_id}
		 WHERE partner_id=#{partner_id}""")
	int updatePartner(Map<String, Object> p);

	@Select("SELECT carrier_code,carrier_name,tracking_url_template FROM the_full_order.tb_dist_carrier WHERE active_yn='Y' ORDER BY carrier_name")
	List<Map<String, Object>> carriers();

	/* ---------- 카테고리 ---------- */
	@Select("""
		SELECT c.category_id,c.parent_category_id,c.category_code,c.category_name,c.sort_order,c.use_yn,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_product d WHERE d.category_id=c.category_id) product_count
		  FROM the_full_order.tb_dist_category c
		 ORDER BY c.sort_order,c.category_name""")
	List<Map<String, Object>> categories();

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_category WHERE category_id=#{category_id}")
	int categoryExists(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_category WHERE category_code=#{category_code} AND category_id<>#{category_id}")
	int sameCategoryCode(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_category(parent_category_id,category_code,category_name,sort_order,use_yn,user_id)
		VALUES(#{parent_category_id},#{category_code},#{category_name},#{sort_order},#{use_yn},#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "category_id", keyColumn = "category_id")
	int insertCategory(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_category SET parent_category_id=#{parent_category_id},category_code=#{category_code},category_name=#{category_name},
		       sort_order=#{sort_order},use_yn=#{use_yn},user_id=#{user_id}
		 WHERE category_id=#{category_id}""")
	int updateCategory(Map<String, Object> p);

	/* ---------- 상품 ---------- */
	/** 목록의 현재가(유형 공통가, 거래처 전용가 제외) */
	@Select("""
		<script>
		SELECT d.product_id,d.product_code,d.product_group_code,d.partner_id,p.partner_name,d.category_id,c.category_name,d.product_name,d.option_name,
		       d.fulfillment_type,d.sale_status,d.approval_status,d.sale_target,d.tax_type,d.storage_type,d.sale_unit,d.shipping_fee,
		       DATE_FORMAT(COALESCE(d.mod_at,d.created_at),'%Y-%m-%d %H:%i') updated_at,
		       (SELECT s.unit_price FROM the_full_order.tb_dist_sale_price_history s
		         WHERE s.product_id=d.product_id AND s.price_type='ACCOUNT' AND s.account_id IS NULL AND s.start_at&lt;=NOW() AND (s.end_at IS NULL OR s.end_at&gt;NOW())
		         ORDER BY s.start_at DESC LIMIT 1) account_price,
		       (SELECT s.unit_price FROM the_full_order.tb_dist_sale_price_history s
		         WHERE s.product_id=d.product_id AND s.price_type='PERSONAL' AND s.account_id IS NULL AND s.start_at&lt;=NOW() AND (s.end_at IS NULL OR s.end_at&gt;NOW())
		         ORDER BY s.start_at DESC LIMIT 1) personal_price,
		       (SELECT h.unit_cost FROM the_full_order.tb_dist_cost_price_history h
		         WHERE h.product_id=d.product_id AND h.start_date&lt;=CURDATE() AND (h.end_date IS NULL OR h.end_date&gt;=CURDATE())
		         ORDER BY h.start_date DESC LIMIT 1) unit_cost,
		       (SELECT i.image_url FROM the_full_order.tb_dist_product_image i WHERE i.product_id=d.product_id
		         ORDER BY i.image_type='MAIN' DESC,i.sort_order,i.product_image_id LIMIT 1) image_url
		  FROM the_full_order.tb_dist_product d
		  JOIN the_full_order.tb_dist_partner p ON p.partner_id=d.partner_id
		  LEFT JOIN the_full_order.tb_dist_category c ON c.category_id=d.category_id
		 <where>
		   <if test="partner_id != null">AND d.partner_id=#{partner_id}</if>
		   <if test="category_id != null">AND d.category_id=#{category_id}</if>
		   <if test="sale_status != null and sale_status != ''">AND d.sale_status=#{sale_status}</if>
		   <if test="fulfillment_type != null and fulfillment_type != ''">AND d.fulfillment_type=#{fulfillment_type}</if>
		   <if test="keyword != null and keyword != ''">AND (d.product_name LIKE CONCAT('%',#{keyword},'%') OR d.product_code LIKE CONCAT('%',#{keyword},'%')
		       OR d.partner_item_code LIKE CONCAT('%',#{keyword},'%'))</if>
		 </where>
		 ORDER BY FIELD(d.sale_status,'ON_SALE','DRAFT','SOLD_OUT','PAUSED','DISCONTINUED'),p.partner_name,d.product_name,d.option_name
		</script>""")
	List<Map<String, Object>> products(Map<String, Object> p);

	@Select("""
		SELECT d.*,DATE_FORMAT(d.sale_start_at,'%Y-%m-%d %H:%i') sale_start_at,DATE_FORMAT(d.sale_end_at,'%Y-%m-%d %H:%i') sale_end_at,
		       p.partner_name,c.category_name,ct.summary,ct.description_html,ct.food_label_json,ct.notice_json
		  FROM the_full_order.tb_dist_product d
		  JOIN the_full_order.tb_dist_partner p ON p.partner_id=d.partner_id
		  LEFT JOIN the_full_order.tb_dist_category c ON c.category_id=d.category_id
		  LEFT JOIN the_full_order.tb_dist_product_content ct ON ct.product_id=d.product_id
		 WHERE d.product_id=#{product_id}""")
	Map<String, Object> product(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_product WHERE product_code=#{product_code} AND product_id<>#{product_id}")
	int sameProductCode(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_sequence(seq_name,seq_date,last_no) VALUES(#{seq_name},CURDATE(),1)
		ON DUPLICATE KEY UPDATE last_no=LAST_INSERT_ID(last_no+1)""")
	int nextSequence(Map<String, Object> p);

	@Select("SELECT last_no FROM the_full_order.tb_dist_sequence WHERE seq_name=#{seq_name} AND seq_date=CURDATE()")
	int currentSequence(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_product(product_code,product_group_code,seller_id,partner_id,category_id,product_name,option_name,partner_item_code,
		       fulfillment_type,sale_status,managed_by,approval_status,sale_target,tax_type,storage_type,origin,sale_unit,unit_content_qty,unit_content_unit,
		       shelf_life_days,min_order_qty,max_order_qty,shipping_fee,free_shipping_amount,bundle_ship_yn,safety_stock_qty,sale_start_at,sale_end_at,user_id)
		VALUES(#{product_code},#{product_group_code},#{seller_id},#{partner_id},#{category_id},#{product_name},#{option_name},#{partner_item_code},
		       #{fulfillment_type},'DRAFT','HQ','APPROVED',#{sale_target},#{tax_type},#{storage_type},#{origin},#{sale_unit},#{unit_content_qty},#{unit_content_unit},
		       #{shelf_life_days},#{min_order_qty},#{max_order_qty},#{shipping_fee},#{free_shipping_amount},#{bundle_ship_yn},#{safety_stock_qty},#{sale_start_at},#{sale_end_at},#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "product_id", keyColumn = "product_id")
	int insertProduct(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_product SET product_code=#{product_code},product_group_code=#{product_group_code},partner_id=#{partner_id},
		       category_id=#{category_id},product_name=#{product_name},option_name=#{option_name},partner_item_code=#{partner_item_code},
		       fulfillment_type=#{fulfillment_type},sale_target=#{sale_target},tax_type=#{tax_type},storage_type=#{storage_type},origin=#{origin},
		       sale_unit=#{sale_unit},unit_content_qty=#{unit_content_qty},unit_content_unit=#{unit_content_unit},shelf_life_days=#{shelf_life_days},
		       min_order_qty=#{min_order_qty},max_order_qty=#{max_order_qty},shipping_fee=#{shipping_fee},free_shipping_amount=#{free_shipping_amount},
		       bundle_ship_yn=#{bundle_ship_yn},safety_stock_qty=#{safety_stock_qty},sale_start_at=#{sale_start_at},sale_end_at=#{sale_end_at},user_id=#{user_id}
		 WHERE product_id=#{product_id}""")
	int updateProduct(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_product SET sale_status=#{sale_status},user_id=#{user_id} WHERE product_id=#{product_id}")
	int updateSaleStatus(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_product_content(product_id,summary,description_html,food_label_json,notice_json,user_id)
		VALUES(#{product_id},#{summary},#{description_html},#{food_label_json},#{notice_json},#{user_id})
		ON DUPLICATE KEY UPDATE summary=VALUES(summary),description_html=VALUES(description_html),food_label_json=VALUES(food_label_json),
		       notice_json=VALUES(notice_json),user_id=VALUES(user_id)""")
	int saveContent(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_product_approval(product_id,request_type,status,before_json,after_json,requester_type,requested_by,reviewed_by,reviewed_at)
		VALUES(#{product_id},#{request_type},'APPROVED',#{before_json},#{after_json},'HQ',#{user_id},#{user_id},NOW())""")
	int insertHqHistory(Map<String, Object> p);

	@Select("""
		SELECT product_approval_id,request_type,status,requester_type,requested_by,DATE_FORMAT(requested_at,'%Y-%m-%d %H:%i') requested_at,
		       reviewed_by,reject_reason,before_json,after_json
		  FROM the_full_order.tb_dist_product_approval WHERE product_id=#{product_id} ORDER BY product_approval_id DESC LIMIT 50""")
	List<Map<String, Object>> history(Map<String, Object> p);

	/* ---------- 이미지 ---------- */
	@Select("SELECT product_image_id,image_type,image_url,thumbnail_url,sort_order FROM the_full_order.tb_dist_product_image WHERE product_id=#{product_id} ORDER BY image_type='MAIN' DESC,sort_order,product_image_id")
	List<Map<String, Object>> images(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_product_image(product_id,image_type,image_url,sort_order,user_id)
		VALUES(#{product_id},#{image_type},#{image_url},(SELECT n FROM (SELECT COALESCE(MAX(sort_order),-1)+1 n FROM the_full_order.tb_dist_product_image WHERE product_id=#{product_id}) t),#{user_id})""")
	int insertImage(Map<String, Object> p);

	@Delete("DELETE FROM the_full_order.tb_dist_product_image WHERE product_image_id=#{product_image_id} AND product_id=#{product_id}")
	int deleteImage(Map<String, Object> p);

	/* ---------- 판매가(유형·거래처별 기간 이력) ---------- */
	@Select("""
		SELECT s.sale_price_id,s.price_type,s.account_id,a.account_name,s.unit_price,DATE_FORMAT(s.start_at,'%Y-%m-%d %H:%i') start_at,
		       DATE_FORMAT(s.end_at,'%Y-%m-%d %H:%i') end_at,s.change_reason,s.user_id,
		       (s.start_at<=NOW() AND (s.end_at IS NULL OR s.end_at>NOW())) current_yn
		  FROM the_full_order.tb_dist_sale_price_history s
		  LEFT JOIN the_full.tb_account a ON a.account_id=s.account_id
		 WHERE s.product_id=#{product_id}
		 ORDER BY s.price_type,s.account_id,s.start_at DESC""")
	List<Map<String, Object>> salePrices(Map<String, Object> p);

	@Select("""
		SELECT COUNT(*) FROM the_full_order.tb_dist_sale_price_history
		 WHERE product_id=#{product_id} AND price_type=#{price_type} AND account_id <=> #{account_id} AND start_at>#{start_at}""")
	int laterSalePrices(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_sale_price_history SET end_at=#{start_at}
		 WHERE product_id=#{product_id} AND price_type=#{price_type} AND account_id <=> #{account_id} AND start_at<=#{start_at}
		   AND (end_at IS NULL OR end_at>#{start_at})""")
	int closeSalePrices(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_sale_price_history(product_id,price_type,account_id,unit_price,start_at,change_reason,user_id)
		VALUES(#{product_id},#{price_type},#{account_id},#{unit_price},#{start_at},#{change_reason},#{user_id})""")
	int insertSalePrice(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_sale_price_history SET end_at=#{end_at} WHERE sale_price_id=#{sale_price_id} AND product_id=#{product_id} AND (end_at IS NULL OR end_at>#{end_at}) AND start_at<#{end_at}")
	int endSalePrice(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_sale_price_history WHERE product_id=#{product_id} AND account_id IS NULL AND start_at<=NOW() AND (end_at IS NULL OR end_at>NOW())")
	int currentSalePriceCount(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full.tb_account WHERE account_id=#{account_id}")
	int accountExists(Map<String, Object> p);

	/* ---------- 매입가(날짜 단위 이력) ---------- */
	@Select("""
		SELECT h.cost_price_id,h.partner_id,p.partner_name,h.unit_cost,DATE_FORMAT(h.start_date,'%Y-%m-%d') start_date,DATE_FORMAT(h.end_date,'%Y-%m-%d') end_date,
		       h.change_reason,h.input_source,h.user_id,(h.start_date<=CURDATE() AND (h.end_date IS NULL OR h.end_date>=CURDATE())) current_yn
		  FROM the_full_order.tb_dist_cost_price_history h JOIN the_full_order.tb_dist_partner p ON p.partner_id=h.partner_id
		 WHERE h.product_id=#{product_id} ORDER BY h.start_date DESC,h.cost_price_id DESC""")
	List<Map<String, Object>> costPrices(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_cost_price_history WHERE product_id=#{product_id} AND start_date>#{start_date}")
	int laterCostPrices(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_cost_price_history SET end_date=DATE_SUB(#{start_date},INTERVAL 1 DAY)
		 WHERE product_id=#{product_id} AND start_date<=#{start_date} AND (end_date IS NULL OR end_date>=#{start_date})""")
	int closeCostPrices(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_cost_price_history(product_id,partner_id,unit_cost,start_date,change_reason,input_source,user_id)
		VALUES(#{product_id},#{partner_id},#{unit_cost},#{start_date},#{change_reason},'HQ',#{user_id})""")
	int insertCostPrice(Map<String, Object> p);

	/* ---------- 판매 가능(기간별 수량) ---------- */
	@Select("""
		SELECT availability_id,DATE_FORMAT(available_from,'%Y-%m-%d') available_from,DATE_FORMAT(available_to,'%Y-%m-%d') available_to,
		       available_qty,reserved_qty,status,note,input_source,user_id,
		       (available_from<=CURDATE() AND (available_to IS NULL OR available_to>=CURDATE())) current_yn
		  FROM the_full_order.tb_dist_product_availability WHERE product_id=#{product_id} ORDER BY available_from DESC""")
	List<Map<String, Object>> availability(Map<String, Object> p);

	@Select("""
		SELECT COUNT(*) FROM the_full_order.tb_dist_product_availability
		 WHERE product_id=#{product_id} AND availability_id<>#{availability_id}
		   AND available_from<=COALESCE(#{available_to},'9999-12-31') AND COALESCE(available_to,'9999-12-31')>=#{available_from}""")
	int overlappingAvailability(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_product_availability(product_id,available_from,available_to,available_qty,status,note,input_source,user_id)
		VALUES(#{product_id},#{available_from},#{available_to},#{available_qty},#{status},#{note},'HQ',#{user_id})""")
	int insertAvailability(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_product_availability SET available_from=#{available_from},available_to=#{available_to},available_qty=#{available_qty},
		       status=#{status},note=#{note},input_source='HQ',user_id=#{user_id}
		 WHERE availability_id=#{availability_id} AND product_id=#{product_id}""")
	int updateAvailability(Map<String, Object> p);

	@Select("SELECT reserved_qty FROM the_full_order.tb_dist_product_availability WHERE availability_id=#{availability_id} AND product_id=#{product_id}")
	java.math.BigDecimal reservedQty(Map<String, Object> p);
}
