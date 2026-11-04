package com.example.demo.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 더채움몰(주문하는 쪽) — 상품 보기·주문·내 주문. 본사·현장 누구나, 주문 조회·취소는 주문한 사람(orderer_user_id)만.
 * 적용 판매가(지금 시각, 유형·거래처별 이력 중 1건):
 *  - 거래처 구매(ACCOUNT): 그 거래처 전용가(거래처가·행사가) > 공통 행사가 > 공통 거래처가
 *  - 개인 구매(PERSONAL): 공통 행사가 > 공통 개인가
 * 판매 가능: 판매중·품절(보이기만) + 본사 승인 + 협력사 사용 + 판매 기간 안 + 판매 대상(ALL 또는 구매 유형).
 */
@Mapper
public interface DistShopMapper {
	String PRICE_FILTER = """
		s.product_id=d.product_id AND s.start_at&lt;=NOW() AND (s.end_at IS NULL OR s.end_at&gt;NOW())
		AND <choose>
		  <when test="buyer_type == 'ACCOUNT'">((s.account_id=#{account_id} AND s.price_type IN ('ACCOUNT','EVENT')) OR (s.account_id IS NULL AND s.price_type IN ('EVENT','ACCOUNT')))</when>
		  <otherwise>(s.account_id IS NULL AND s.price_type IN ('EVENT','PERSONAL'))</otherwise>
		</choose>
		ORDER BY (s.account_id IS NOT NULL) DESC,(s.price_type='EVENT') DESC,s.start_at DESC LIMIT 1""";

	/** 정상가(행사가 뺀 같은 규칙) — 행사가일 때 몰 화면에서 할인율·원래 가격을 보여 주는 용도. 주문 금액 계산엔 안 쓴다 */
	String REGULAR_PRICE_FILTER = """
		s.product_id=d.product_id AND s.start_at&lt;=NOW() AND (s.end_at IS NULL OR s.end_at&gt;NOW())
		AND <choose>
		  <when test="buyer_type == 'ACCOUNT'">s.price_type='ACCOUNT' AND (s.account_id=#{account_id} OR s.account_id IS NULL)</when>
		  <otherwise>s.account_id IS NULL AND s.price_type='PERSONAL'</otherwise>
		</choose>
		ORDER BY (s.account_id IS NOT NULL) DESC,s.start_at DESC LIMIT 1""";

	String SHOP_PRODUCTS = """
		SELECT * FROM (
		SELECT d.product_id,d.product_code,d.product_group_code,d.product_name,d.option_name,d.category_id,c.category_name,d.partner_id,p.partner_name,
		       d.fulfillment_type,d.sale_status,d.tax_type,d.storage_type,d.origin,d.sale_unit,d.unit_content_qty,d.unit_content_unit,
		       d.min_order_qty,d.max_order_qty,d.shipping_fee,d.free_shipping_amount,d.bundle_ship_yn,ct.summary,
		       (SELECT s.sale_price_id FROM the_full_order.tb_dist_sale_price_history s WHERE\s""" + PRICE_FILTER + """
		       ) sale_price_id,
		       (SELECT s.unit_price FROM the_full_order.tb_dist_sale_price_history s WHERE\s""" + PRICE_FILTER + """
		       ) unit_price,
		       (SELECT s.price_type FROM the_full_order.tb_dist_sale_price_history s WHERE\s""" + PRICE_FILTER + """
		       ) price_type,
		       (SELECT s.unit_price FROM the_full_order.tb_dist_sale_price_history s WHERE\s""" + REGULAR_PRICE_FILTER + """
		       ) regular_price,
		       (SELECT i.image_url FROM the_full_order.tb_dist_product_image i WHERE i.product_id=d.product_id
		         ORDER BY i.image_type='MAIN' DESC,i.sort_order,i.product_image_id LIMIT 1) image_url,
		       a.availability_id,a.status availability_status,a.available_qty,a.reserved_qty,a.note availability_note
		  FROM the_full_order.tb_dist_product d
		  JOIN the_full_order.tb_dist_partner p ON p.partner_id=d.partner_id AND p.active_yn='Y'
		  LEFT JOIN the_full_order.tb_dist_category c ON c.category_id=d.category_id
		  LEFT JOIN the_full_order.tb_dist_product_content ct ON ct.product_id=d.product_id
		  LEFT JOIN the_full_order.tb_dist_product_availability a ON a.product_id=d.product_id
		       AND a.available_from&lt;=CURDATE() AND (a.available_to IS NULL OR a.available_to&gt;=CURDATE())
		 WHERE d.sale_status IN ('ON_SALE','SOLD_OUT') AND d.approval_status='APPROVED'
		   AND (d.sale_start_at IS NULL OR d.sale_start_at&lt;=NOW()) AND (d.sale_end_at IS NULL OR d.sale_end_at&gt;NOW())
		   AND d.sale_target IN ('ALL',#{buyer_type})
		   <if test="product_id != null">AND d.product_id=#{product_id}</if>
		   <if test="product_ids != null and product_ids.size() > 0">AND d.product_id IN <foreach collection="product_ids" item="id" open="(" separator="," close=")">#{id}</foreach></if>
		   <if test="category_id != null">AND (d.category_id=#{category_id} OR c.parent_category_id=#{category_id})</if>
		   <if test="keyword != null and keyword != ''">AND (d.product_name LIKE CONCAT('%',#{keyword},'%') OR p.partner_name LIKE CONCAT('%',#{keyword},'%')
		       OR ct.summary LIKE CONCAT('%',#{keyword},'%'))</if>
		) x WHERE x.unit_price IS NOT NULL
		ORDER BY x.sale_status='SOLD_OUT',x.category_id IS NULL,x.category_id,x.product_name,x.option_name""";

	@Select("<script>" + SHOP_PRODUCTS + "</script>")
	List<Map<String, Object>> products(Map<String, Object> p);

	@Select("SELECT description_html,food_label_json,notice_json FROM the_full_order.tb_dist_product_content WHERE product_id=#{product_id}")
	Map<String, Object> content(Map<String, Object> p);

	@Select("SELECT image_type,image_url FROM the_full_order.tb_dist_product_image WHERE product_id=#{product_id} ORDER BY image_type='MAIN' DESC,sort_order,product_image_id")
	List<Map<String, Object>> images(Map<String, Object> p);

	@Select("""
		SELECT c.category_id,c.category_name,c.parent_category_id
		  FROM the_full_order.tb_dist_category c
		 WHERE c.use_yn='Y' AND EXISTS(SELECT 1 FROM the_full_order.tb_dist_product d WHERE d.sale_status='ON_SALE'
		       AND (d.category_id=c.category_id OR d.category_id IN (SELECT k.category_id FROM the_full_order.tb_dist_category k WHERE k.parent_category_id=c.category_id)))
		 ORDER BY c.sort_order,c.category_name""")
	List<Map<String, Object>> categories();

	/* ---------- 주문 준비 ---------- */
	@Select("SELECT account_id,account_name FROM the_full.tb_account WHERE account_id=#{account_id}")
	Map<String, Object> account(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_account_profile WHERE account_id=#{account_id}")
	Map<String, Object> accountProfile(Map<String, Object> p);

	/** 아직 청구·입금되지 않은 후불 주문 금액(한도 확인용) */
	@Select("""
		SELECT COALESCE(SUM(total_amount-paid_amount-refunded_amount),0) FROM the_full_order.tb_dist_order
		 WHERE account_id=#{account_id} AND payment_status='CREDIT' AND order_status<>'CANCELED'""")
	BigDecimal outstandingCredit(Map<String, Object> p);

	@Select("""
		SELECT availability_id,status,available_qty,reserved_qty FROM the_full_order.tb_dist_product_availability
		 WHERE product_id=#{product_id} AND available_from<=CURDATE() AND (available_to IS NULL OR available_to>=CURDATE()) FOR UPDATE""")
	Map<String, Object> lockAvailability(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_product_availability SET reserved_qty=reserved_qty+#{qty} WHERE availability_id=#{availability_id}")
	int reserve(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_product_availability SET reserved_qty=GREATEST(0,reserved_qty-#{qty})
		 WHERE product_id=#{product_id} AND available_from<=#{order_date} AND (available_to IS NULL OR available_to>=#{order_date})""")
	int release(Map<String, Object> p);

	@Select("""
		SELECT unit_cost FROM the_full_order.tb_dist_cost_price_history
		 WHERE product_id=#{product_id} AND start_date<=CURDATE() AND (end_date IS NULL OR end_date>=CURDATE()) ORDER BY start_date DESC LIMIT 1""")
	BigDecimal currentCost(Map<String, Object> p);

	@Select("SELECT customer_id FROM the_full_order.tb_dist_customer WHERE login_user_id=#{login_user_id}")
	Long customerId(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_customer(login_user_id,account_id,customer_name_enc,customer_name_masked,phone_enc,phone_hash,phone_masked,email_enc,privacy_agreed_at,user_id)
		VALUES(#{login_user_id},#{account_id},#{name_enc},#{name_masked},#{phone_enc},#{phone_hash},#{phone_masked},#{email_enc},NOW(),#{login_user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "customer_id", keyColumn = "customer_id")
	int insertCustomer(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_customer SET account_id=#{account_id},customer_name_enc=#{name_enc},customer_name_masked=#{name_masked},phone_enc=#{phone_enc},
		       phone_hash=#{phone_hash},phone_masked=#{phone_masked},email_enc=#{email_enc},user_id=#{login_user_id}
		 WHERE customer_id=#{customer_id}""")
	int updateCustomer(Map<String, Object> p);

	/* ---------- 주문 저장 ---------- */
	@Select("SELECT order_id,orderer_user_id FROM the_full_order.tb_dist_order WHERE client_request_key=#{client_request_key}")
	Map<String, Object> orderByRequestKey(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_order(order_no,client_request_key,seller_id,order_channel,buyer_type,account_id,customer_id,orderer_user_id,
		       orderer_name_enc,orderer_phone_enc,orderer_email_enc,order_status,payment_status,item_amount,shipping_fee,discount_amount,total_amount,
		       taxable_amount,vat_amount,tax_free_amount,receipt_type,request_note,paid_at,user_id)
		VALUES(#{order_no},#{client_request_key},#{seller_id},'ORDER_APP',#{buyer_type},#{account_id},#{customer_id},#{orderer_user_id},
		       #{orderer_name_enc},#{orderer_phone_enc},#{orderer_email_enc},#{order_status},#{payment_status},#{item_amount},#{shipping_fee},0,#{total_amount},
		       #{taxable_amount},#{vat_amount},#{tax_free_amount},#{receipt_type},#{request_note},NULL,#{orderer_user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "order_id", keyColumn = "order_id")
	int insertOrder(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_order_item(order_id,line_no,product_id,partner_id,fulfillment_type,product_code,product_name,option_name,tax_type,
		       sale_price_id,qty,unit_price,discount_amount,amount,unit_cost)
		VALUES(#{order_id},#{line_no},#{product_id},#{partner_id},#{fulfillment_type},#{product_code},#{product_name},#{option_name},#{tax_type},
		       #{sale_price_id},#{qty},#{unit_price},0,#{amount},#{unit_cost})""")
	int insertItem(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_order_recipient(order_id,recipient_name_enc,recipient_phone_enc,zipcode,address1_enc,address2_enc,delivery_request,user_id)
		VALUES(#{order_id},#{recipient_name_enc},#{recipient_phone_enc},#{zipcode},#{address1_enc},#{address2_enc},#{delivery_request},#{user_id})""")
	int insertRecipient(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_payment(order_id,payment_method,status,amount,bank_code,deposit_account_no,depositor_name,deposit_due_at,user_id)
		VALUES(#{order_id},#{payment_method},#{status},#{amount},#{bank_name},#{deposit_account_no},#{depositor_name},#{deposit_due_at},#{user_id})""")
	int insertPayment(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_order_status_history(order_id,status_kind,from_status,to_status,reason,source_type,changed_by)
		VALUES(#{order_id},#{status_kind},#{from_status},#{to_status},#{reason},'USER',#{user_id})""")
	int insertHistory(Map<String, Object> p);

	/* ---------- 내 주문 ---------- */
	@Select("""
		<script>
		SELECT o.order_id,o.order_no,o.buyer_type,o.account_id,o.order_status,o.payment_status,o.total_amount,
		       DATE_FORMAT(o.ordered_at,'%Y-%m-%d %H:%i') ordered_at,
		       (SELECT i.product_name FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id ORDER BY i.line_no LIMIT 1) first_item_name,
		       (SELECT i.option_name FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id ORDER BY i.line_no LIMIT 1) first_item_option,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id) item_count,
		       (SELECT im.image_url FROM the_full_order.tb_dist_order_item i JOIN the_full_order.tb_dist_product_image im ON im.product_id=i.product_id
		         WHERE i.order_id=o.order_id ORDER BY i.line_no,im.image_type='MAIN' DESC,im.sort_order LIMIT 1) image_url,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_shipment s WHERE s.order_id=o.order_id AND s.tracking_no IS NOT NULL) tracking_count,
		       (SELECT c.status FROM the_full_order.tb_dist_claim c WHERE c.order_id=o.order_id AND c.claim_type='CANCEL' ORDER BY c.claim_id DESC LIMIT 1) cancel_claim_status
		  FROM the_full_order.tb_dist_order o
		 WHERE o.orderer_user_id=#{user_id}
		   <if test="status == 'ACTIVE'">AND o.order_status NOT IN ('CANCELED','COMPLETED','DELIVERED')</if>
		   <if test="status == 'DONE'">AND o.order_status IN ('CANCELED','COMPLETED','DELIVERED')</if>
		 ORDER BY o.ordered_at DESC,o.order_id DESC LIMIT 200
		</script>""")
	List<Map<String, Object>> myOrders(Map<String, Object> p);

	@Select("""
		SELECT o.*,DATE_FORMAT(o.ordered_at,'%Y-%m-%d %H:%i') ordered_at,DATE_FORMAT(o.paid_at,'%Y-%m-%d %H:%i') paid_at,
		       DATE_FORMAT(o.canceled_at,'%Y-%m-%d %H:%i') canceled_at,DATE(o.ordered_at) order_date,a.account_name
		  FROM the_full_order.tb_dist_order o LEFT JOIN the_full.tb_account a ON a.account_id=o.account_id
		 WHERE o.order_id=#{order_id} AND o.orderer_user_id=#{user_id}""")
	Map<String, Object> myOrder(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_order WHERE order_id=#{order_id} AND orderer_user_id=#{user_id} FOR UPDATE")
	Map<String, Object> lockMyOrder(Map<String, Object> p);

	@Select("""
		SELECT i.order_item_id,i.line_no,i.product_id,i.product_code,i.product_name,i.option_name,i.fulfillment_type,i.tax_type,i.qty,i.unit_price,i.amount,
		       i.item_status,i.shipped_qty,i.canceled_qty,p.partner_name,
		       (SELECT im.image_url FROM the_full_order.tb_dist_product_image im WHERE im.product_id=i.product_id ORDER BY im.image_type='MAIN' DESC,im.sort_order LIMIT 1) image_url
		  FROM the_full_order.tb_dist_order_item i JOIN the_full_order.tb_dist_partner p ON p.partner_id=i.partner_id
		 WHERE i.order_id=#{order_id} ORDER BY i.line_no""")
	List<Map<String, Object>> items(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_order_recipient WHERE order_id=#{order_id}")
	Map<String, Object> recipient(Map<String, Object> p);

	@Select("""
		SELECT payment_id,payment_method,status,amount,paid_amount,canceled_amount,bank_code bank_name,deposit_account_no,depositor_name,
		       DATE_FORMAT(deposit_due_at,'%Y-%m-%d %H:%i') deposit_due_at,DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i') paid_at
		  FROM the_full_order.tb_dist_payment WHERE order_id=#{order_id} ORDER BY payment_id""")
	List<Map<String, Object>> payments(Map<String, Object> p);

	@Select("""
		SELECT s.shipment_id,s.shipment_kind,s.status,s.carrier_code,c.carrier_name,s.tracking_no,
		       CASE WHEN s.tracking_no IS NOT NULL AND c.tracking_url_template IS NOT NULL THEN REPLACE(c.tracking_url_template,'{tracking_no}',s.tracking_no) END tracking_url,
		       DATE_FORMAT(s.invoiced_at,'%Y-%m-%d %H:%i') invoiced_at,DATE_FORMAT(s.delivered_at,'%Y-%m-%d %H:%i') delivered_at,
		       (SELECT GROUP_CONCAT(CONCAT(i.product_name,IFNULL(CONCAT(' ',i.option_name),''),' ×',TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM si.qty))) SEPARATOR ', ')
		          FROM the_full_order.tb_dist_shipment_item si JOIN the_full_order.tb_dist_order_item i ON i.order_item_id=si.order_item_id
		         WHERE si.shipment_id=s.shipment_id) item_summary
		  FROM the_full_order.tb_dist_shipment s LEFT JOIN the_full_order.tb_dist_carrier c ON c.carrier_code=s.carrier_code
		 WHERE s.order_id=#{order_id} ORDER BY s.shipment_id""")
	List<Map<String, Object>> shipments(Map<String, Object> p);

	@Select("""
		SELECT e.shipment_id,e.event_status,e.event_location,e.event_description,DATE_FORMAT(e.event_at,'%Y-%m-%d %H:%i') event_at
		  FROM the_full_order.tb_dist_tracking_event e JOIN the_full_order.tb_dist_shipment s ON s.shipment_id=e.shipment_id
		 WHERE s.order_id=#{order_id} ORDER BY e.event_at DESC""")
	List<Map<String, Object>> trackingEvents(Map<String, Object> p);

	@Select("""
		SELECT status_kind,from_status,to_status,reason,source_type,DATE_FORMAT(changed_at,'%Y-%m-%d %H:%i') changed_at
		  FROM the_full_order.tb_dist_order_status_history WHERE order_id=#{order_id} AND status_kind IN ('ORDER','PAYMENT') ORDER BY status_history_id""")
	List<Map<String, Object>> history(Map<String, Object> p);

	@Select("""
		SELECT claim_id,claim_no,claim_type,reason_code,reason_detail,status,refund_amount,reject_reason,DATE_FORMAT(requested_at,'%Y-%m-%d %H:%i') requested_at
		  FROM the_full_order.tb_dist_claim WHERE order_id=#{order_id} ORDER BY claim_id DESC""")
	List<Map<String, Object>> claims(Map<String, Object> p);

	/* ---------- 취소 ---------- */
	@Update("""
		UPDATE the_full_order.tb_dist_order SET order_status='CANCELED',payment_status=#{payment_status},canceled_at=NOW(),cancel_reason=#{reason},user_id=#{user_id}
		 WHERE order_id=#{order_id}""")
	int cancelOrder(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_order_item SET item_status='CANCELED',canceled_qty=qty WHERE order_id=#{order_id}")
	int cancelItems(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_payment SET status='CANCELED',canceled_amount=paid_amount,canceled_at=NOW(),user_id=#{user_id}
		 WHERE order_id=#{order_id} AND status IN ('READY','WAITING_DEPOSIT')""")
	int cancelPayments(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_order_item WHERE order_id=#{order_id} AND item_status NOT IN ('ORDERED','CANCELED')")
	int progressedItems(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_claim WHERE order_id=#{order_id} AND claim_type='CANCEL' AND status IN ('REQUESTED','APPROVED')")
	int openCancelClaims(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_claim(claim_no,order_id,claim_type,reason_code,reason_detail,fault_party,status,refund_amount,requested_by,completed_at,user_id)
		VALUES(#{claim_no},#{order_id},'CANCEL',#{reason_code},#{reason_detail},'CUSTOMER',#{status},#{refund_amount},#{user_id},
		       CASE WHEN #{status}='COMPLETED' THEN NOW() END,#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "claim_id", keyColumn = "claim_id")
	int insertCancelClaim(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_claim_item(claim_id,order_item_id,qty,refund_amount)
		SELECT #{claim_id},order_item_id,qty-canceled_qty,amount FROM the_full_order.tb_dist_order_item WHERE order_id=#{order_id} AND item_status<>'CANCELED'""")
	int insertCancelClaimItems(Map<String, Object> p);
}
