package io.seata.samples.integration.account.tcc;

import io.seata.samples.integration.account.mapper.TAccountMapper;
import io.seata.samples.integration.common.dubbo.AccountTccAction;
import io.seata.samples.integration.common.mapper.TccTransactionControlMapper;
import io.seata.samples.integration.common.tcc.TccControlSupport;
import io.seata.samples.integration.common.util.TccContextUtil;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户服务的 TCC 三阶段实现。
 *
 * <pre>
 *   Try     prepare : frozen += amount                      （校验 amount - frozen >= 本次）
 *   Confirm confirm : amount -= amount, frozen -= amount
 *   Cancel  cancel  : frozen -= amount
 * </pre>
 *
 * <p>每个阶段都是「先过控制表、再动业务数据」，两步在同一个本地事务里，
 * 这样控制表状态与 {@code t_account} 的数值永远同生共死。</p>
 *
 * @author lidong
 */
@DubboService(version = "1.0.0", timeout = 30000)
@Service
@Slf4j
public class AccountTccActionImpl implements AccountTccAction {

    /** 与 {@code @TwoPhaseBusinessAction(name = ...)} 保持一致，仅用于控制表留痕 */
    private static final String ACTION_NAME = "accountTccAction";

    @Autowired
    private TAccountMapper accountMapper;

    @Autowired
    private TccTransactionControlMapper controlMapper;

    /**
     * Try 阶段。
     *
     * <p><b>⚠️ 参数注解必须与接口 {@code AccountTccAction#prepare} 保持一致（重复一遍）</b>：
     * 见 {@link io.seata.samples.integration.common.dubbo.StorageTccAction} 类注释
     * —— CGLIB 代理下 Seata 读的是实现类方法上的参数注解。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean prepare(BusinessActionContext context,
                           @BusinessActionContextParameter(paramName = "userId") String userId,
                           @BusinessActionContextParameter(paramName = "amount") double amount) {
        log.info("[TCC-Try][account] {} userId={}, amount={}",
                TccContextUtil.describe(context), userId, amount);

        if (!TccContextUtil.inGlobalBranch(context)) {
            log.warn("[TCC-Try][account] 未处于全局事务中，退化为本地预留");
            return doFreeze(userId, amount);
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.tryPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Try][account] 分支已登记，幂等跳过");
            return true;
        }
        return doFreeze(userId, amount);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean confirm(BusinessActionContext context) {
        log.info("[TCC-Confirm][account] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.confirmPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            // 空提交 / 重复提交 / 已被 Cancel：不动账，直接成功
            log.info("[TCC-Confirm][account] 无需扣款（空提交或幂等重放）");
            return true;
        }

        String userId = TccContextUtil.getString(context, "userId");
        Double amount = TccContextUtil.getDouble(context, "amount");
        int rows = accountMapper.confirmFreeze(userId, amount);
        if (rows <= 0) {
            // 控制表已置 CONFIRMED，但业务扣减没落到行 —— 必须抛出，让本地事务整体回滚，
            // 否则控制表与账目会永久不一致（TC 会重试 Confirm，届时状态仍是 CONFIRMED 而跳过）
            throw new IllegalStateException("Confirm 扣款影响行数为 0：userId=" + userId + ", amount=" + amount);
        }
        log.info("[TCC-Confirm][account] 扣款成功 userId={}, amount={}", userId, amount);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancel(BusinessActionContext context) {
        log.info("[TCC-Cancel][account] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.cancelPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            // 空回滚 / 重复回滚（Try 没预留过，或已经被别的 Cancel 解冻过）
            log.info("[TCC-Cancel][account] 无需解冻（空回滚或幂等重放）");
            return true;
        }

        String userId = TccContextUtil.getString(context, "userId");
        Double amount = TccContextUtil.getDouble(context, "amount");
        int rows = accountMapper.releaseFreeze(userId, amount);
        if (rows <= 0) {
            throw new IllegalStateException("Cancel 解冻影响行数为 0：userId=" + userId + ", amount=" + amount);
        }
        log.info("[TCC-Cancel][account] 解冻成功 userId={}, amount={}", userId, amount);
        return true;
    }

    private boolean doFreeze(String userId, double amount) {
        int rows = accountMapper.freezeAmount(userId, amount);
        if (rows <= 0) {
            throw new IllegalStateException("账户可用余额不足，冻结失败：userId=" + userId + ", amount=" + amount);
        }
        log.info("[TCC-Try][account] 冻结成功 userId={}, amount={}", userId, amount);
        return true;
    }
}
