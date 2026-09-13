package io.seata.samples.integration.common.dubbo;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * 订单服务的 TCC 契约（Dubbo 暴露）。
 *
 * <pre>
 *   Try     prepare : insert t_order(status=0 TRYING) + 调 account 的 Try
 *   Confirm confirm : status 0 → 1（CONFIRMED）
 *   Cancel  cancel  : status 0 → 2（CANCELED）
 * </pre>
 *
 * <p>订单服务是链路上唯一「既是被调用方、又是调用方」的参与方：
 * 它的 Try 内部会继续发起对账户服务的 Try 调用，两者共享同一个 XID，
 * 因此会各自注册成独立分支，二阶段由 TC 分别驱动。</p>
 *
 * <p><b>⚠️ 实现类必须重复标注 {@link BusinessActionContextParameter}</b>，
 * 原因见 {@link StorageTccAction} 的类注释（CGLIB 代理下 Seata 取的是实现类方法）。</p>
 *
 * @author lidong
 */
@LocalTCC
public interface OrderTccAction {

    /**
     * Try 阶段：落一条"占位"订单（status=0），并预留账户金额
     */
    @TwoPhaseBusinessAction(name = "orderTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext context,
                    @BusinessActionContextParameter(paramName = "orderNo") String orderNo,
                    @BusinessActionContextParameter(paramName = "userId") String userId,
                    @BusinessActionContextParameter(paramName = "commodityCode") String commodityCode,
                    @BusinessActionContextParameter(paramName = "count") int count,
                    @BusinessActionContextParameter(paramName = "amount") double amount);

    /**
     * Confirm 阶段：把占位订单置为已确认
     */
    boolean confirm(BusinessActionContext context);

    /**
     * Cancel 阶段：把占位订单置为已取消
     */
    boolean cancel(BusinessActionContext context);
}
