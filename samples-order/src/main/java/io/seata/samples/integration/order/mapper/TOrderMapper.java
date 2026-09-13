package io.seata.samples.integration.order.mapper;

import com.baomidou.mybatisplus.mapper.BaseMapper;
import io.seata.samples.integration.order.entity.TOrder;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * * @author lidong
 * @since 2019-09-04
 */
public interface TOrderMapper extends BaseMapper<TOrder> {

    /**
     * 创建订单（控制器单服务自测入口，直接落一条 status=0 的记录）
     * @Param:  order 订单信息
     */
    void createOrder(@Param("order") TOrder order);

    // ==================== 以下为 TCC 模式新增 ====================

    /**
     * Try：落一条「占位」订单，status=0(TRYING)。
     * orderNo 上加唯一索引，重复 Try 会因唯一键冲突而失败，作为幂等的第二道防线。
     */
    int insertTryingOrder(@Param("order") TOrder order);

    /**
     * Confirm：status 0(TRYING) → 1(CONFIRMED)，影响行数为 0 说明状态已变（幂等或异常）
     */
    int confirmOrder(@Param("orderNo") String orderNo);

    /**
     * Cancel：status 0(TRYING) → 2(CANCELED)，影响行数为 0 说明状态已变
     */
    int cancelOrder(@Param("orderNo") String orderNo);
}
