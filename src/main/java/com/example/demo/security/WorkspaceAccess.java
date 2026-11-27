package com.example.demo.security;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class WorkspaceAccess {
    private final JdbcTemplate jdbc;
    public WorkspaceAccess(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
    public static boolean protectedPath(String path) {
        return Set.of("v2","Procurement","Order","Inventory","Menu","Table","Account","Analytics","AI")
            .contains(path.split("/",3).length>1 ? path.split("/",3)[1] : "");
    }
    /**
     * 로그인 없이(손님) 읽을 수 있는 GET(2026-11-12 사용자 결정: 로그인 안 하면 더채움몰 말고는 접근 불가, 홈은 손님도 봄).
     * 더채움몰 상품 보기(개인 구매가)·홈 인스타그램만. 장바구니 금액·주문·내 주문은 로그인 필요.
     * 로그인 세션이 있으면 평소처럼 그 사용자로 처리한다.
     */
    public static boolean guestReadable(String path) {
        return path.equals("/v2/dist/shop/context") || path.equals("/v2/dist/shop/products") || path.startsWith("/v2/dist/shop/products/")
            || path.equals("/v2/workspace/instagram");
    }
    /** 로그인 세션 사용자, 없으면 null(손님). 세션은 있는데 역할이 맞지 않으면 user()와 같이 막는다 */
    public static WorkspaceUser optionalUser(HttpServletRequest request) {
        var session=request.getSession(false);
        return session==null || session.getAttribute(WorkspaceUser.SESSION_KEY)==null ? null : user(request);
    }
    public static WorkspaceUser user(HttpServletRequest request) {
        var session=request.getSession(false);
        Object value=session==null ? null : session.getAttribute(WorkspaceUser.SESSION_KEY);
        if(!(value instanceof WorkspaceUser user)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"다시 로그인해주세요.");
        if(!Set.of("2","3").contains(user.userType())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"업무 역할을 확인해주세요.");
        if(!user.headquarters() && user.accountId().isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"소속 거래처가 지정되지 않았습니다.");
        return user;
    }
    public void authorize(WorkspaceUser user,String path,String method) {
        if(user.headquarters()) return;
        boolean read="GET".equals(method);
        boolean headquarters=path.startsWith("/v2/supplier-integration/site-mapping")
            || path.startsWith("/Order/Welstory") || path.startsWith("/v2/supplier-integration/sync/")
            || path.equals("/Order/ItemLookup") || path.equals("/Order/workplaceLookup")
            || (!read && path.equals("/v2/supplier-integration/offers/ingest"))
            || path.equals("/v2/supplier-integration/ourhome/sync-catalog")
            || path.startsWith("/Analytics/Hygiene/Action")
            || path.equals("/Analytics/Dashboard")
            || path.equals("/v2/workspace/hq-overview")
            || path.startsWith("/v2/supplier-integration/ourhome/")
            || path.equals("/Menu/MenuSave") || path.equals("/Menu/RecipeSave")
            || path.startsWith("/v2/ai-usage")
            || (!read && path.equals("/v2/ingredients"))
            || (!read && path.startsWith("/v2/catalog/"))
            // 본사 금액대 식단표 템플릿 관리(조회·불러오기는 현장도 가능)
            || (!read && path.startsWith("/v2/meal-plan-templates"))
            // 본사 끼니 종류 관리(조회는 현장도 가능)
            || (!read && path.startsWith("/v2/meal-slots"))
            || (!read && path.equals("/v2/supplier-integration/sites"))
            // 위생 점검 카테고리 설정(조회는 현장도 가능). 기록 삭제는 HygieneService에서 본사만
            || (!read && path.equals("/v2/hygiene/categories"))
            // 더채움 유통 협력사 관리(본사가 유통 관리자). 주문하는 더채움몰은 본사·현장 모두
            || path.startsWith("/v2/dist/admin")
            // 고객사 초기 세팅: 공급사 구매 이력 가져오기·식재료 연결·단가 이력(2026-11-19)
            || path.startsWith("/v2/purchase-history")
            // 매입 관리(ASP, 2026-11-23): 우선 본사만(사용자 결정)
            || path.startsWith("/v2/purchase-ledger");
        if(headquarters) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"본사 담당자만 사용할 수 있습니다.");
    }
    public void checkAccount(WorkspaceUser user,String account) {
        if(!user.headquarters() && account!=null && !account.isBlank() && !user.accountId().equals(account))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"다른 거래처의 업무에 접근할 수 없습니다.");
    }
    public void checkResource(WorkspaceUser user,String key,String value) {
        if(user.headquarters() || value==null || value.isBlank()) return;
        String table=switch(key) {
            case "inventory_balance_id" -> "tb_account_inventory_balance";
            case "account_recipe_detail_id" -> "tb_account_recipe_detail";
            case "meal_service_id" -> "tb_account_meal_service";
            case "purchase_order_id" -> "tb_account_purchase_order";
            case "table_id" -> "tb_account_table_meals";
            case "supplier_account_site_id" -> "tb_supplier_account_site";
            case "procurement_cart_id" -> "tb_account_procurement_cart";
            case "meal_detail_id" -> "tb_account_table_meals_detail";
            default -> null;
        };
        if(table!=null) {
            var owners=jdbc.queryForList("SELECT account_id FROM "+table+" WHERE "+key+"=?",String.class,value);
            for(String owner:owners) checkAccount(user,owner);
        }
        if("supplier_offer_id".equals(key)) {
            // 상품 제공 조건은 사업장 단위로 여러 거래처가 함께 쓴다 → 내 거래처가 그 사업장에 연결된 적이 있으면 허용
            var exists=jdbc.queryForObject("SELECT COUNT(*) FROM tb_supplier_site_product_offer WHERE supplier_offer_id=?",Integer.class,value);
            var mine=jdbc.queryForObject("SELECT COUNT(*) FROM tb_supplier_account_site s"+com.example.demo.service.SupplierPriceSite.join("s")
                +"JOIN tb_supplier_site_product_offer o ON o.supplier_site_id="+com.example.demo.service.SupplierPriceSite.id("s")
                +" WHERE o.supplier_offer_id=? AND s.account_id=?",Integer.class,value,user.accountId());
            if(exists!=null && exists>0 && (mine==null || mine==0))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"다른 거래처의 업무에 접근할 수 없습니다.");
        }
        if("procurement_cart_item_id".equals(key)) {
            var owners=jdbc.queryForList("SELECT c.account_id FROM tb_account_procurement_cart_item i JOIN tb_account_procurement_cart c ON c.procurement_cart_id=i.procurement_cart_id WHERE i.procurement_cart_item_id=?",String.class,value);
            for(String owner:owners) checkAccount(user,owner);
        }
        if("purchase_order_item_id".equals(key)) {
            var owners=jdbc.queryForList("SELECT p.account_id FROM tb_account_purchase_order_item i JOIN tb_account_purchase_order p ON p.purchase_order_id=i.purchase_order_id WHERE i.purchase_order_item_id=?",String.class,value);
            for(String owner:owners) checkAccount(user,owner);
        }
    }
    public void checkBody(WorkspaceUser user,JsonNode body) {
        if(body==null || body.isNull()) return;
        if(body.isObject()) body.fields().forEachRemaining(entry -> {
            if("account_id".equals(entry.getKey())) checkAccount(user,entry.getValue().asText());
            if(entry.getValue().isValueNode()) checkResource(user,entry.getKey(),entry.getValue().asText());
            else checkBody(user,entry.getValue());
        });
        else if(body.isArray()) body.forEach(value -> checkBody(user,value));
    }
}
