package com.example.demo.mapper;
import java.util.List; import java.util.Map; import org.apache.ibatis.annotations.Mapper;
@Mapper public interface ProcurementCrudMapper {
 List<Map<String,Object>> analysis(Map<String,Object>p); List<Map<String,Object>> shortages(Map<String,Object>p); List<Map<String,Object>> carts(Map<String,Object>p); List<Map<String,Object>> cartItems(Map<String,Object>p);
 int insertCart(Map<String,Object>p); int updateCart(Map<String,Object>p); int deleteCart(Map<String,Object>p); int deleteCartItems(Map<String,Object>p); int insertCartItem(Map<String,Object>p); int updateCartItem(Map<String,Object>p); int deleteCartItem(Map<String,Object>p); int insertMealShortages(Map<String,Object>p);
 List<Map<String,Object>> orders(Map<String,Object>p); List<Map<String,Object>> orderItems(Map<String,Object>p); int insertOrder(Map<String,Object>p); int updateOrder(Map<String,Object>p); int deleteOrder(Map<String,Object>p); int deleteOrderItems(Map<String,Object>p); int insertOrderItem(Map<String,Object>p); int updateOrderItem(Map<String,Object>p); int deleteOrderItem(Map<String,Object>p);
}
