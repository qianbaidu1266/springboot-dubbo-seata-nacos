package io.seata.samples.integration.storage.tcc;

import io.seata.samples.integration.common.dubbo.StorageTccAction;
import io.seata.samples.integration.common.mapper.TccTransactionControlMapper;
import io.seata.samples.integration.common.tcc.TccControlSupport;
import io.seata.samples.integration.common.util.TccContextUtil;
import io.seata.samples.integration.storage.mapper.TStorageMapper;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存服务的 TCC 三阶段实现。
 *
 * <pre>
 *   Try     prepare : frozen += count                       （校验 count - frozen >= 本次）
 *   Confirm confirm : count -= count, frozen -= count
 *   Cancel  cancel  : frozen -= count
 * </pre>
 *
 * @author lidong
 */
@DubboService(version = "1.0.0", timeout = 30000)
@Service
@Slf4j
public class StorageTccActionImpl implements StorageTccAction {

    private static final String ACTION_NAME = "storageTccAction";

    @Autowired
    private TStorageMapper storageMapper;

    @Autowired
    private TccTransactionControlMapper controlMapper;

    /**
     * Try 阶段。
     *
     * <p><b>⚠️ 参数注解必须与接口 {@code StorageTccAction#prepare} 保持一致（重复一遍）</b>：
     * Seata 的 {@code ActionInterceptorHandler#fetchActionRequestContext} 是从
     * {@code invocation.getMethod().getParameterAnnotations()} 里读的，而本项目因为
     * {@code @Transactional} 使 Bean 走 CGLIB 代理，这里拿到的是<b>实现类方法</b>，
     * 接口上的注解不会被继承。少标一次，二阶段就拿不到任何业务参数。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean prepare(BusinessActionContext context,
                           @BusinessActionContextParameter(paramName = "commodityCode") String commodityCode,
                           @BusinessActionContextParameter(paramName = "count") int count) {
        log.info("[TCC-Try][storage] {} commodityCode={}, count={}",
                TccContextUtil.describe(context), commodityCode, count);

        if (!TccContextUtil.inGlobalBranch(context)) {
            log.warn("[TCC-Try][storage] 未处于全局事务中，退化为本地预留");
            return doFreeze(commodityCode, count);
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.tryPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Try][storage] 分支已登记，幂等跳过");
            return true;
        }
        return doFreeze(commodityCode, count);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean confirm(BusinessActionContext context) {
        log.info("[TCC-Confirm][storage] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.confirmPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Confirm][storage] 无需扣减（空提交或幂等重放）");
            return true;
        }

        String commodityCode = TccContextUtil.getString(context, "commodityCode");
        Integer count = TccContextUtil.getInteger(context, "count");
        int rows = storageMapper.confirmFreeze(commodityCode, count);
        if (rows <= 0) {
            throw new IllegalStateException("Confirm 扣减影响行数为 0：commodityCode=" + commodityCode + ", count=" + count);
        }
        log.info("[TCC-Confirm][storage] 扣减成功 commodityCode={}, count={}", commodityCode, count);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancel(BusinessActionContext context) {
        log.info("[TCC-Cancel][storage] {}", TccContextUtil.describe(context));
        if (!TccContextUtil.inGlobalBranch(context)) {
            return true;
        }

        String xid = context.getXid();
        long branchId = context.getBranchId();
        if (!TccControlSupport.cancelPhase(controlMapper, xid, branchId, ACTION_NAME)) {
            log.info("[TCC-Cancel][storage] 无需解冻（空回滚或幂等重放）");
            return true;
        }

        String commodityCode = TccContextUtil.getString(context, "commodityCode");
        Integer count = TccContextUtil.getInteger(context, "count");
        int rows = storageMapper.releaseFreeze(commodityCode, count);
        if (rows <= 0) {
            throw new IllegalStateException("Cancel 解冻影响行数为 0：commodityCode=" + commodityCode + ", count=" + count);
        }
        log.info("[TCC-Cancel][storage] 解冻成功 commodityCode={}, count={}", commodityCode, count);
        return true;
    }

    private boolean doFreeze(String commodityCode, int count) {
        int rows = storageMapper.freezeStorage(commodityCode, count);
        if (rows <= 0) {
            throw new IllegalStateException("库存可用量不足，冻结失败：commodityCode=" + commodityCode + ", count=" + count);
        }
        log.info("[TCC-Try][storage] 冻结成功 commodityCode={}, count={}", commodityCode, count);
        return true;
    }
}
