package io.seata.samples.integration.common.dubbo;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * 库存服务的 TCC 契约（Dubbo 暴露）。
 *
 * <p>一个业务操作被拆成三个显式方法，补偿逻辑由业务自己实现，Seata 只负责
 * 传播 XID 并在二阶段按 {@code commitMethod} / {@code rollbackMethod} 回调：</p>
 *
 * <pre>
 *   Try     prepare : frozen += count        —— 预留库存，count 不动
 *   Confirm confirm : count -= count, frozen -= count  —— 真正扣减
 *   Cancel  cancel  : frozen -= count        —— 释放预留
 * </pre>
 *
 * <p>注意：{@code prepare} 的第一个参数 {@link BusinessActionContext} 由 Seata 的
 * TCC 拦截器注入，调用方传 {@code null} 即可；其余参数上的
 * {@link BusinessActionContextParameter} 会被序列化进分支上下文，
 * 二阶段的 confirm / cancel 正是靠它取回 Try 时的业务参数。</p>
 *
 * <p><b>⚠️ 实现类必须重复标注这些注解</b>：Seata 的
 * {@code ActionInterceptorHandler#fetchActionRequestContext} 读的是
 * {@code invocation.getMethod().getParameterAnnotations()}。当 Bean 被 CGLIB 代理
 * （本项目因 {@code @Transactional} 落在实现类上而走 CGLIB）时，这里拿到的是<b>实现类方法</b>，
 * 接口上的注解不会被继承 —— 只标接口会导致二阶段参数全是 null。详见
 * {@code documents/问题记录.md}。</p>
 *
 * @author lidong
 */
@LocalTCC
public interface StorageTccAction {

    /**
     * Try 阶段：冻结（预留）库存
     */
    @TwoPhaseBusinessAction(name = "storageTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext context,
                    @BusinessActionContextParameter(paramName = "commodityCode") String commodityCode,
                    @BusinessActionContextParameter(paramName = "count") int count);

    /**
     * Confirm 阶段：真正扣减库存并释放冻结额度
     */
    boolean confirm(BusinessActionContext context);

    /**
     * Cancel 阶段：释放冻结额度（不回滚业务数据，因为 Try 阶段根本没扣）
     */
    boolean cancel(BusinessActionContext context);
}
