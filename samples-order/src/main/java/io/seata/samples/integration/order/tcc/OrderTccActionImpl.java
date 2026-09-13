package io.seata.samples.integration.order.tcc;

import io.seata.samples.integration.common.dubbo.AccountTccAction;
import io.seata.samples.integration.common.dubbo.OrderTccAction;
import io.seata.samples.integration.common.mapper.TccTransactionControlMapper;
import io.seata.samples.integration.common.tcc.TccControlSupport;
import io.seata.samples.integration.common.util.TccContextUtil;
import io.seata.samples.integration.order.entity.TOrder;
import io.seata.samples.integration.order.mapper.TOrderMapper;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单服务的 TCC 三阶段实现。
 *
 * <pre>
 *   Try     prepare : insert t_order(status=0 TRYING) + 调 account 的 Try
 *   Confirm confirm : status 0 → 1(CONFIRMED)
 *   Cancel  cancel  : status 0 → 2(CANCELED)
 * </pre>
 *
 * <p>订单是链路上唯一「既当提供者、又当消费者」的一环：它的 Try 内部会继续
 * 发起对账户服务的 Try 调用，两者共享同一个 XID。XID 由
 * {@code dubbo-filter-seata} 透传，因此账户侧会注册成<b>另一个独立分支</b>，
 * 全局事务结束时不走订单的 Cancel，而是由 TC 分别驱动两个分支（见
 * {@code documents/Seata事务模式原理-AT-TCC对比分析.md} §9 的时序说明）。</p>
 *
 * @author lidong
 */
@DubboService(version = "1.0.0", timeout = 30000)
@Service
@Slf4j
public class OrderTccActionImpl implements OrderTccAction {

    private static final String ACTION_NAME = "orderTccAction";

    @Autowired
    private TOrderMapper orderMapper;

    @Autowired
    private TccTransactionControlMapper controlMapper;

    @DubboReference(version = "1.0.0")
    private AccountTccAction accountTccAction;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean prepare(BusinessActionContext context,
                           @BusinessActionContextParameter(paramName = "orderNo") String orderNo,
                           @BusinessActionContextParameter(paramName = "userId") String userId,
                           @BusinessActionContextParameter(paramName = "commodityCode") String commodityCode,
                           @BusinessActionContextParameter(paramName = "count") int count,
                           @BusinessActionContextParameter(paramName = "amount") double amount) {
        log.info("[TCC-Try][order] {} orderNo={}, userId={}, count={}, amount={}",
                TccContextUtil.describe(context), orderNo, userId, count, amount);

        if (!TccContextUtil.inGlobalBranch(context)) {
            log.warn("[TCC-Try][order] 未处于全局事务中，退化为本地建单");
            return doInsertTryingOrder(orderNo, userId, commodityCode, count, amount, false);
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.tryPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Try][order] 分支已登记，幂等跳过");
            return true;
        }

        // 1) 本地落占位订单（status=0）
        doInsertTryingOrder(orderNo, userId, commodityCode, count, amount, true);

        // 2) 同一全局事务下，继续预留账户金额（嵌套 TCC 分支）
        //    这里 context 传 null —— 真正的上下文由 Seata 在账户侧注入
        accountTccAction.prepare(null, userId, amount);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean confirm(BusinessActionContext context) {
        log.info("[TCC-Confirm][order] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.confirmPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Confirm][order] 无需改状态（空提交或幂等重放）");
            return true;
        }

        String orderNo = TccContextUtil.getString(context, "orderNo");
        int rows = orderMapper.confirmOrder(orderNo);
        if (rows <= 0) {
            throw new IllegalStateException("Confirm 订单状态流转影响行数为 0：orderNo=" + orderNo);
        }
        log.info("[TCC-Confirm][order] 订单生效 orderNo={}", orderNo);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancel(BusinessActionContext context) {
        log.info("[TCC-Cancel][order] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.cancelPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Cancel][order] 无需改状态（空回滚或幂等重放）");
            return true;
        }

        String orderNo = TccContextUtil.getString(context, "orderNo");
        int rows = orderMapper.cancelOrder(orderNo);
        if (rows <= 0) {
            throw new IllegalStateException("Cancel 订单状态流转影响行数为 0：orderNo=" + orderNo);
        }
        log.info("[TCC-Cancel][order] 订单已取消 orderNo={}", orderNo);
        return true;
    }

    private boolean doInsertTryingOrder(String orderNo, String userId, String commodityCode,
                                        int count, double amount, boolean strict) {
        TOrder order = new TOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setCommodityCode(commodityCode);
        order.setCount(count);
        order.setAmount(amount);
        order.setStatus(0);
        int rows = orderMapper.insertTryingOrder(order);
        if (rows <= 0 && strict) {
            throw new IllegalStateException("占位订单写入失败：orderNo=" + orderNo);
        }
        log.info("[TCC-Try][order] 占位订单已写入 orderNo={}, status=TRYING", orderNo);
        return true;
    }
}
