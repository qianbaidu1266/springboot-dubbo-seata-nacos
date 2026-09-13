package io.seata.samples.integration.common.dubbo;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * 账户服务的 TCC 契约（Dubbo 暴露）。
 *
 * <pre>
 *   Try     prepare : frozen += amount                       —— 冻结金额，amount 不动
 *   Confirm confirm : amount -= amount, frozen -= amount     —— 真正扣款
 *   Cancel  cancel  : frozen -= amount                       —— 解冻
 * </pre>
 *
 * <p>与 AT 模式最本质的差别就在这里：Try 阶段<b>不会</b>真正动 amount，
 * 所以中间态对其它事务是"可见但不可用"的 —— 靠 frozen 字段表达，
 * 而不是靠数据库行锁把别人挡住。</p>
 *
 * <p><b>⚠️ 实现类必须重复标注 {@link BusinessActionContextParameter}</b>，
 * 原因见 {@link StorageTccAction} 的类注释（CGLIB 代理下 Seata 取的是实现类方法）。</p>
 *
 * @author lidong
 */
@LocalTCC
public interface AccountTccAction {

    /**
     * Try 阶段：冻结（预留）金额
     */
    @TwoPhaseBusinessAction(name = "accountTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext context,
                    @BusinessActionContextParameter(paramName = "userId") String userId,
                    @BusinessActionContextParameter(paramName = "amount") double amount);

    /**
     * Confirm 阶段：真正扣减余额并释放冻结额度
     */
    boolean confirm(BusinessActionContext context);

    /**
     * Cancel 阶段：解冻金额
     */
    boolean cancel(BusinessActionContext context);
}
