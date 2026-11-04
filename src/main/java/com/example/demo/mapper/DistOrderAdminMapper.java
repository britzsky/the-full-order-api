package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 더채움 유통 — 본사 주문 처리(입금 확인·직송 발주서·송장·배송 완료·취소·환불)와 거래처 결제 설정. 본사 전용(/v2/dist/admin).
 * 주문 조회는 DistShopMapper의 품목·결제·배송 조회를 같이 쓴다(본사는 orderer 조건 없이).
 */
@Mapper
public interface DistOrderAdminMapper {
	/* ---------- 목록·일감 ---------- */
	String ORDER_FLAGS = """
		(o.order_status='PENDING_PAYMENT') need_deposit,
		(o.order_status IN ('PAID','PREPARING') AND EXISTS(SELECT 1 FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id
		     AND i.fulfillment_type='DROP_SHIP' AND i.item_status='ORDERED')) need_po,
		(o.order_status IN ('PAID','PREPARING','PARTIALLY_SHIPPED') AND EXISTS(SELECT 1 FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id
		     AND i.item_status NOT IN ('CANCELED','SHIPPED','DELIVERED','RETURNED') AND i.qty-i.canceled_qty-i.shipped_qty&gt;0)) need_invoice,
		EXISTS(SELECT 1 FROM the_full_order.tb_dist_claim c WHERE c.order_id=o.order_id AND c.claim_type='CANCEL' AND c.status='REQUESTED') cancel_requested""";

	@Select("""
		<script>
		SELECT o.order_id,o.order_no,o.buyer_type,o.account_id,a.account_name,cu.customer_name_masked,o.orderer_user_id,o.order_status,o.payment_status,
		       o.total_amount,o.paid_amount,DATE_FORMAT(o.ordered_at,'%Y-%m-%d %H:%i') ordered_at,o.admin_note,
		       (SELECT p.payment_method FROM the_full_order.tb_dist_payment p WHERE p.order_id=o.order_id ORDER BY p.payment_id DESC LIMIT 1) payment_method,
		       (SELECT DATE_FORMAT(p.deposit_due_at,'%Y-%m-%d') FROM the_full_order.tb_dist_payment p WHERE p.order_id=o.order_id ORDER BY p.payment_id DESC LIMIT 1) deposit_due_date,
		       (SELECT CONCAT(i.product_name,IFNULL(CONCAT(' ',i.option_name),'')) FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id ORDER BY i.line_no LIMIT 1) first_item,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id) item_count,
		       (SELECT GROUP_CONCAT(DISTINCT pt.partner_name SEPARATOR ', ') FROM the_full_order.tb_dist_order_item i
		          JOIN the_full_order.tb_dist_partner pt ON pt.partner_id=i.partner_id WHERE i.order_id=o.order_id) partner_names,
		""" + ORDER_FLAGS + """
		  FROM the_full_order.tb_dist_order o
		  LEFT JOIN the_full.tb_account a ON a.account_id=o.account_id
		  LEFT JOIN the_full_order.tb_dist_customer cu ON cu.customer_id=o.customer_id
		 <where>
		   <if test="status != null and status != ''">AND o.order_status=#{status}</if>
		   <if test="buyer_type != null and buyer_type != ''">AND o.buyer_type=#{buyer_type}</if>
		   <if test="partner_id != null">AND EXISTS(SELECT 1 FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id AND i.partner_id=#{partner_id})</if>
		   <if test="from != null">AND o.ordered_at&gt;=#{from}</if>
		   <if test="to != null">AND o.ordered_at&lt;DATE_ADD(#{to},INTERVAL 1 DAY)</if>
		   <if test="keyword != null and keyword != ''">AND (o.order_no LIKE CONCAT('%',#{keyword},'%') OR a.account_name LIKE CONCAT('%',#{keyword},'%')
		       OR o.orderer_user_id LIKE CONCAT('%',#{keyword},'%')
		       OR EXISTS(SELECT 1 FROM the_full_order.tb_dist_order_item i WHERE i.order_id=o.order_id AND i.product_name LIKE CONCAT('%',#{keyword},'%')))</if>
		 </where>
		 ORDER BY o.ordered_at DESC,o.order_id DESC LIMIT 300
		</script>""")
	List<Map<String, Object>> orders(Map<String, Object> p);

	/** 일감 수: 입금 확인 대기·발주 필요·송장 필요·취소 요청 */
	@Select("""
		<script>
		SELECT COALESCE(SUM(x.need_deposit),0) need_deposit,COALESCE(SUM(x.need_po),0) need_po,COALESCE(SUM(x.need_invoice),0) need_invoice,
		       COALESCE(SUM(x.cancel_requested),0) cancel_requested
		  FROM (SELECT """ + ORDER_FLAGS + """
		          FROM the_full_order.tb_dist_order o WHERE o.order_status NOT IN ('CANCELED','DELIVERED','COMPLETED')) x
		</script>""")
	Map<String, Object> workCounts();

	@Select("""
		SELECT o.*,DATE_FORMAT(o.ordered_at,'%Y-%m-%d %H:%i') ordered_at,DATE_FORMAT(o.paid_at,'%Y-%m-%d %H:%i') paid_at,
		       DATE_FORMAT(o.canceled_at,'%Y-%m-%d %H:%i') canceled_at,DATE(o.ordered_at) order_date,a.account_name
		  FROM the_full_order.tb_dist_order o LEFT JOIN the_full.tb_account a ON a.account_id=o.account_id WHERE o.order_id=#{order_id}""")
	Map<String, Object> order(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_order WHERE order_id=#{order_id} FOR UPDATE")
	Map<String, Object> lockOrder(Map<String, Object> p);

	@Select("""
		SELECT i.*,p.partner_name,p.po_send_method,p.po_send_target,p.default_carrier_code FROM the_full_order.tb_dist_order_item i
		  JOIN the_full_order.tb_dist_partner p ON p.partner_id=i.partner_id WHERE i.order_id=#{order_id} ORDER BY i.line_no""")
	List<Map<String, Object>> items(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_order SET admin_note=#{admin_note},user_id=#{user_id} WHERE order_id=#{order_id}")
	int saveNote(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_order SET order_status=#{order_status},user_id=#{user_id} WHERE order_id=#{order_id}")
	int setOrderStatus(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_order SET order_status=#{order_status},
		       shipped_at=CASE WHEN #{order_status}='SHIPPED' THEN COALESCE(shipped_at,NOW()) ELSE shipped_at END,
		       delivered_at=CASE WHEN #{order_status}='DELIVERED' THEN COALESCE(delivered_at,NOW()) ELSE delivered_at END,user_id=#{user_id}
		 WHERE order_id=#{order_id}""")
	int setShippingStatus(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_order_status_history(order_id,order_item_id,status_kind,from_status,to_status,reason,source_type,changed_by)
		VALUES(#{order_id},#{order_item_id},#{status_kind},#{from_status},#{to_status},#{reason},#{source_type},#{user_id})""")
	int insertHistory(Map<String, Object> p);

	/* ---------- 입금 확인 ---------- */
	@Select("SELECT * FROM the_full_order.tb_dist_payment WHERE order_id=#{order_id} ORDER BY payment_id DESC LIMIT 1 FOR UPDATE")
	Map<String, Object> lockPayment(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_payment SET status='PAID',paid_amount=#{amount},paid_at=#{paid_at},depositor_name=COALESCE(#{depositor_name},depositor_name),user_id=#{user_id}
		 WHERE payment_id=#{payment_id}""")
	int markPaymentPaid(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_order SET order_status='PAID',payment_status='PAID',paid_amount=#{amount},paid_at=#{paid_at},user_id=#{user_id}
		 WHERE order_id=#{order_id}""")
	int markOrderPaid(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_payment_transaction(payment_id,tx_type,amount,status,claim_id,refund_bank_code,refund_account_no_enc,refund_holder_name,
		       reason,completed_at,user_id)
		VALUES(#{payment_id},#{tx_type},#{amount},'SUCCESS',#{claim_id},#{refund_bank_code},#{refund_account_no_enc},#{refund_holder_name},#{reason},NOW(),#{user_id})""")
	int insertTransaction(Map<String, Object> p);

	@Select("""
		SELECT t.payment_tx_id,t.tx_type,t.amount,t.status,t.refund_bank_code,t.refund_holder_name,t.reason,t.user_id,
		       DATE_FORMAT(t.completed_at,'%Y-%m-%d %H:%i') completed_at
		  FROM the_full_order.tb_dist_payment_transaction t JOIN the_full_order.tb_dist_payment p ON p.payment_id=t.payment_id
		 WHERE p.order_id=#{order_id} ORDER BY t.payment_tx_id""")
	List<Map<String, Object>> transactions(Map<String, Object> p);

	/* ---------- 직송 발주서 ---------- */
	@Insert("""
		INSERT INTO the_full_order.tb_dist_purchase_order(po_no,partner_id,po_type,order_id,status,send_method,total_amount,expected_date,note,sent_at,user_id)
		VALUES(#{po_no},#{partner_id},'DROP_SHIP',#{order_id},'SENT',#{send_method},#{total_amount},#{expected_date},#{note},NOW(),#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "purchase_order_id", keyColumn = "purchase_order_id")
	int insertPurchaseOrder(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_purchase_order_item(purchase_order_id,product_id,order_item_id,qty,unit_cost,amount)
		VALUES(#{purchase_order_id},#{product_id},#{order_item_id},#{qty},#{unit_cost},#{amount})""")
	int insertPurchaseOrderItem(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_order_item SET item_status=#{item_status} WHERE order_item_id=#{order_item_id}")
	int setItemStatus(Map<String, Object> p);

	@Select("""
		SELECT po.purchase_order_id,po.po_no,po.partner_id,pt.partner_name,pt.manager_name,pt.manager_phone,pt.po_send_target,po.status,po.send_method,
		       po.confirm_source,po.confirmed_by,po.total_amount,DATE_FORMAT(po.expected_date,'%Y-%m-%d') expected_date,po.note,
		       DATE_FORMAT(po.sent_at,'%Y-%m-%d %H:%i') sent_at,DATE_FORMAT(po.confirmed_at,'%Y-%m-%d %H:%i') confirmed_at
		  FROM the_full_order.tb_dist_purchase_order po JOIN the_full_order.tb_dist_partner pt ON pt.partner_id=po.partner_id
		 WHERE po.order_id=#{order_id} ORDER BY po.purchase_order_id""")
	List<Map<String, Object>> purchaseOrders(Map<String, Object> p);

	@Select("""
		SELECT pi.purchase_order_id,pi.order_item_id,pi.qty,pi.unit_cost,pi.amount,pi.done_qty,pi.status,
		       oi.product_code,oi.product_name,oi.option_name,d.partner_item_code
		  FROM the_full_order.tb_dist_purchase_order_item pi
		  JOIN the_full_order.tb_dist_order_item oi ON oi.order_item_id=pi.order_item_id
		  JOIN the_full_order.tb_dist_product d ON d.product_id=pi.product_id
		 WHERE oi.order_id=#{order_id}
		 ORDER BY pi.purchase_order_item_id""")
	List<Map<String, Object>> purchaseOrderItems(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_purchase_order WHERE purchase_order_id=#{purchase_order_id} FOR UPDATE")
	Map<String, Object> lockPurchaseOrder(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_purchase_order SET status='CONFIRMED',confirm_source='HQ',confirmed_by=#{user_id},confirmed_at=NOW(),
		       expected_date=COALESCE(#{expected_date},expected_date),note=COALESCE(#{note},note),user_id=#{user_id}
		 WHERE purchase_order_id=#{purchase_order_id}""")
	int confirmPurchaseOrder(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_purchase_order_item SET done_qty=LEAST(qty,done_qty+#{qty}),status=CASE WHEN done_qty>=qty THEN 'COMPLETED' ELSE 'PARTIAL' END
		 WHERE order_item_id=#{order_item_id} AND status NOT IN ('CANCELED','COMPLETED')""")
	int addPurchaseDone(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_purchase_order po SET status=CASE
		         WHEN NOT EXISTS(SELECT 1 FROM the_full_order.tb_dist_purchase_order_item pi WHERE pi.purchase_order_id=po.purchase_order_id AND pi.status NOT IN ('COMPLETED','CANCELED')) THEN 'COMPLETED'
		         WHEN EXISTS(SELECT 1 FROM the_full_order.tb_dist_purchase_order_item pi WHERE pi.purchase_order_id=po.purchase_order_id AND pi.done_qty>0) THEN 'PARTIAL'
		         ELSE po.status END,
		       completed_at=CASE WHEN NOT EXISTS(SELECT 1 FROM the_full_order.tb_dist_purchase_order_item pi WHERE pi.purchase_order_id=po.purchase_order_id AND pi.status NOT IN ('COMPLETED','CANCELED')) THEN COALESCE(completed_at,NOW()) ELSE completed_at END
		 WHERE po.order_id=#{order_id} AND po.status NOT IN ('CANCELED','COMPLETED')""")
	int refreshPurchaseOrders(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_purchase_order_item pi JOIN the_full_order.tb_dist_purchase_order po ON po.purchase_order_id=pi.purchase_order_id SET pi.status='CANCELED' WHERE po.order_id=#{order_id} AND pi.status<>'COMPLETED'")
	int cancelPurchaseOrderItems(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_purchase_order SET status='CANCELED',user_id=#{user_id} WHERE order_id=#{order_id} AND status NOT IN ('COMPLETED')")
	int cancelPurchaseOrders(Map<String, Object> p);

	/* ---------- 송장·배송 ---------- */
	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_carrier WHERE carrier_code=#{carrier_code} AND active_yn='Y'")
	int carrierExists(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_dist_shipment WHERE carrier_code=#{carrier_code} AND tracking_no=#{tracking_no}")
	int trackingExists(Map<String, Object> p);

	@Select("SELECT purchase_order_id FROM the_full_order.tb_dist_purchase_order WHERE order_id=#{order_id} AND partner_id=#{partner_id} AND status<>'CANCELED' ORDER BY purchase_order_id DESC LIMIT 1")
	Long purchaseOrderFor(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_shipment(order_id,shipment_kind,ship_from_type,partner_id,purchase_order_id,carrier_code,tracking_no,status,
		       invoice_source,invoice_input_by,invoiced_at,user_id)
		VALUES(#{order_id},'OUTBOUND',#{ship_from_type},#{partner_id},#{purchase_order_id},#{carrier_code},#{tracking_no},'INVOICED','HQ',#{user_id},NOW(),#{user_id})""")
	@Options(useGeneratedKeys = true, keyProperty = "shipment_id", keyColumn = "shipment_id")
	int insertShipment(Map<String, Object> p);

	@Insert("INSERT INTO the_full_order.tb_dist_shipment_item(shipment_id,order_item_id,qty) VALUES(#{shipment_id},#{order_item_id},#{qty})")
	int insertShipmentItem(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_order_item SET shipped_qty=shipped_qty+#{qty},
		       item_status=CASE WHEN shipped_qty+#{qty}>=qty-canceled_qty THEN 'SHIPPED' ELSE item_status END
		 WHERE order_item_id=#{order_item_id}""")
	int addShipped(Map<String, Object> p);

	@Select("SELECT * FROM the_full_order.tb_dist_shipment WHERE shipment_id=#{shipment_id} FOR UPDATE")
	Map<String, Object> lockShipment(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_shipment SET status='DELIVERED',delivered_at=NOW(),user_id=#{user_id} WHERE shipment_id=#{shipment_id}")
	int markDelivered(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_order_item i SET i.item_status='DELIVERED'
		 WHERE i.order_id=#{order_id} AND i.item_status='SHIPPED'
		   AND NOT EXISTS(SELECT 1 FROM the_full_order.tb_dist_shipment_item si JOIN the_full_order.tb_dist_shipment s ON s.shipment_id=si.shipment_id
		                   WHERE si.order_item_id=i.order_item_id AND s.status<>'DELIVERED')""")
	int markItemsDelivered(Map<String, Object> p);

	@Select("""
		SELECT s.shipment_id,s.ship_from_type,s.partner_id,pt.partner_name,s.carrier_code,c.carrier_name,s.tracking_no,s.status,s.invoice_input_by,
		       CASE WHEN s.tracking_no IS NOT NULL AND c.tracking_url_template IS NOT NULL THEN REPLACE(c.tracking_url_template,'{tracking_no}',s.tracking_no) END tracking_url,
		       DATE_FORMAT(s.invoiced_at,'%Y-%m-%d %H:%i') invoiced_at,DATE_FORMAT(s.delivered_at,'%Y-%m-%d %H:%i') delivered_at,
		       (SELECT GROUP_CONCAT(CONCAT(i.product_name,IFNULL(CONCAT(' ',i.option_name),''),' ×',TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM si.qty))) SEPARATOR ', ')
		          FROM the_full_order.tb_dist_shipment_item si JOIN the_full_order.tb_dist_order_item i ON i.order_item_id=si.order_item_id
		         WHERE si.shipment_id=s.shipment_id) item_summary
		  FROM the_full_order.tb_dist_shipment s LEFT JOIN the_full_order.tb_dist_carrier c ON c.carrier_code=s.carrier_code
		  LEFT JOIN the_full_order.tb_dist_partner pt ON pt.partner_id=s.partner_id
		 WHERE s.order_id=#{order_id} ORDER BY s.shipment_id""")
	List<Map<String, Object>> shipments(Map<String, Object> p);

	/* ---------- 취소·클레임 ---------- */
	@Select("SELECT * FROM the_full_order.tb_dist_claim WHERE claim_id=#{claim_id} FOR UPDATE")
	Map<String, Object> lockClaim(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_claim SET status=#{status},refund_amount=#{refund_amount},reject_reason=#{reject_reason},
		       approved_at=CASE WHEN #{status}='COMPLETED' THEN NOW() ELSE approved_at END,
		       completed_at=CASE WHEN #{status} IN ('COMPLETED','REJECTED') THEN NOW() ELSE completed_at END,user_id=#{user_id}
		 WHERE claim_id=#{claim_id}""")
	int closeClaim(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_dist_claim_item SET refund_amount=CASE WHEN #{refund} THEN refund_amount ELSE 0 END WHERE claim_id=#{claim_id}")
	int settleClaimItems(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_payment SET status='CANCELED',canceled_amount=paid_amount,canceled_at=NOW(),user_id=#{user_id}
		 WHERE order_id=#{order_id} AND status IN ('READY','WAITING_DEPOSIT','PAID')""")
	int cancelPayments(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_dist_order SET order_status='CANCELED',payment_status=#{payment_status},refunded_amount=#{refunded_amount},
		       canceled_at=NOW(),cancel_reason=#{reason},user_id=#{user_id}
		 WHERE order_id=#{order_id}""")
	int cancelOrder(Map<String, Object> p);

	/* ---------- 거래처 결제 설정 ---------- */
	@Select("""
		<script>
		SELECT a.account_id,a.account_name,pf.payment_terms,pf.credit_limit,pf.credit_hold_yn,pf.order_enabled_yn,pf.billing_close_day,pf.payment_due_days,
		       (pf.account_id IS NOT NULL) has_profile,
		       (SELECT COALESCE(SUM(o.total_amount-o.paid_amount-o.refunded_amount),0) FROM the_full_order.tb_dist_order o
		         WHERE o.account_id=a.account_id AND o.payment_status='CREDIT' AND o.order_status&lt;&gt;'CANCELED') outstanding_credit,
		       (SELECT COUNT(*) FROM the_full_order.tb_dist_order o WHERE o.account_id=a.account_id) order_count
		  FROM the_full.tb_account a
		  LEFT JOIN the_full_order.tb_dist_account_profile pf ON pf.account_id=a.account_id
		 WHERE (a.del_yn IS NULL OR a.del_yn&lt;&gt;'Y')
		   <if test="keyword != null and keyword != ''">AND (a.account_name LIKE CONCAT('%',#{keyword},'%') OR a.account_id LIKE CONCAT('%',#{keyword},'%'))</if>
		   <if test="only_set">AND (pf.account_id IS NOT NULL OR EXISTS(SELECT 1 FROM the_full_order.tb_dist_order o WHERE o.account_id=a.account_id))</if>
		 ORDER BY (pf.account_id IS NOT NULL) DESC,a.account_name LIMIT 200
		</script>""")
	List<Map<String, Object>> accountProfiles(Map<String, Object> p);

	@Select("SELECT pf.*,a.account_name FROM the_full.tb_account a LEFT JOIN the_full_order.tb_dist_account_profile pf ON pf.account_id=a.account_id WHERE a.account_id=#{account_id}")
	Map<String, Object> accountProfile(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_dist_account_profile(account_id,tax_invoice_email,tax_invoice_manager,payment_terms,credit_limit,billing_close_day,payment_due_days,
		       credit_hold_yn,default_recipient_name,default_phone,default_zipcode,default_address1,default_address2,order_enabled_yn,user_id)
		VALUES(#{account_id},#{tax_invoice_email},#{tax_invoice_manager},#{payment_terms},#{credit_limit},#{billing_close_day},#{payment_due_days},
		       #{credit_hold_yn},#{default_recipient_name},#{default_phone},#{default_zipcode},#{default_address1},#{default_address2},#{order_enabled_yn},#{user_id})
		ON DUPLICATE KEY UPDATE tax_invoice_email=VALUES(tax_invoice_email),tax_invoice_manager=VALUES(tax_invoice_manager),payment_terms=VALUES(payment_terms),
		       credit_limit=VALUES(credit_limit),billing_close_day=VALUES(billing_close_day),payment_due_days=VALUES(payment_due_days),credit_hold_yn=VALUES(credit_hold_yn),
		       default_recipient_name=VALUES(default_recipient_name),default_phone=VALUES(default_phone),default_zipcode=VALUES(default_zipcode),
		       default_address1=VALUES(default_address1),default_address2=VALUES(default_address2),order_enabled_yn=VALUES(order_enabled_yn),user_id=VALUES(user_id)""")
	int saveAccountProfile(Map<String, Object> p);
}
