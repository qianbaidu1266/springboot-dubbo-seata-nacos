package io.seata.samples.integration.common.tcc;

import io.seata.samples.integration.common.enums.TccActionStatus;
import io.seata.samples.integration.common.mapper.TccTransactionControlMapper;

/**
 * TCC 三阶段的状态机支撑（幂等 / 空回滚 / 防悬挂）。
 *
 * <p>把"什么时候该真正执行业务动作"从各 RM 的 Action 实现里抽出来，
 * 三个服务共用一套判定；每个方法都返回一个布尔值告诉调用方
 * <b>要不要继续做业务动作</b>：</p>
 *
 * <pre>
 *   阶段      返回 true                   返回 false
 *   --------  --------------------------  ----------------------------------
 *   Try      首次执行 → 做预留            重复 Try（幂等）→ 直接返回成功
 *   Confirm  本次才流转 → 做真正扣减      空提交 / 已确认 / 已取消 → 跳过
 *   Cancel   本次才流转 → 做预留释放      空回滚 / 已取消 / 已确认 → 跳过
 * </pre>
 *
 * <p><b>防悬挂</b>是唯一需要"报错"的分支：Cancel 先到时会在控制表里留下一条
 * CANCELED 记录（空回滚），迟到的 Try 查到它就必须拒绝执行，否则预留的资源
 * 再也没有人来解冻。所以 {@link #tryPhase} 在这种情形下抛
 * {@link IllegalStateException}，由调用方所在事务整体回滚。</p>
 *
 * <p>调用约定：这三个方法都必须在<b>已经开启的本地事务</b>中调用
 * （Action 实现类上标 {@code @Transactional}），保证控制表状态与业务数据同生共死。</p>
 *
 * @author lidong
 */
public final class TccControlSupport {

    private TccControlSupport() {
    }

    /**
     * Try 阶段：登记分支为 TRIED。
     *
     * @return {@code true} 表示首次执行，调用方必须继续做「资源预留」；
     *         {@code false} 表示重复 Try，按幂等直接返回成功
     * @throws IllegalStateException 检测到悬挂（Cancel 已先到达），调用方须失败退出
     */
    public static boolean tryPhase(TccTransactionControlMapper mapper,
                                   String xid, long branchId, String actionName) {
        TccActionStatus current = TccActionStatus.of(mapper.selectStatus(xid, branchId));
        if (current == null) {
            mapper.insertControl(xid, branchId, actionName, TccActionStatus.TRIED.getCode());
            return true;
        }
        if (current == TccActionStatus.CANCELED) {
            // 悬挂：空回滚时留下的 CANCELED 记录，说明 Cancel 比 Try 先到
            throw new IllegalStateException("TCC 悬挂：分支已被 Cancel，拒绝执行 Try"
                    + " [xid=" + xid + ", branchId=" + branchId + ", action=" + actionName + "]");
        }
        // TRIED（重复 Try）/ CONFIRMED（已提交）：幂等跳过，预留不再重复累加
        return false;
    }

    /**
     * Confirm 阶段：TRIED → CONFIRMED。
     *
     * @return {@code true} 表示本次才流转成功，调用方必须做「真正扣减」；
     *         {@code false} 表示空提交或重复提交，跳过业务动作
     */
    public static boolean confirmPhase(TccTransactionControlMapper mapper,
                                       String xid, long branchId, String actionName) {
        TccActionStatus current = TccActionStatus.of(mapper.selectStatus(xid, branchId));
        if (current == null) {
            // 空提交：Try 从未成功，却收到 Confirm。补一条 CONFIRMED 兜住后续迟到的 Try
            mapper.insertControl(xid, branchId, actionName, TccActionStatus.CONFIRMED.getCode());
            return false;
        }
        if (current == TccActionStatus.TRIED) {
            mapper.updateStatus(xid, branchId,
                    TccActionStatus.TRIED.getCode(), TccActionStatus.CONFIRMED.getCode());
            return true;
        }
        // CONFIRMED（幂等重试）/ CANCELED（已回滚，不该再提交）：跳过
        return false;
    }

    /**
     * Cancel 阶段：无记录 → CANCELED（空回滚），TRIED → CANCELED。
     *
     * @return {@code true} 表示本次才流转成功，调用方必须做「释放预留」；
     *         {@code false} 表示空回滚或重复回滚，跳过业务动作
     */
    public static boolean cancelPhase(TccTransactionControlMapper mapper,
                                      String xid, long branchId, String actionName) {
        TccActionStatus current = TccActionStatus.of(mapper.selectStatus(xid, branchId));
        if (current == null) {
            // 空回滚：Try 没成功（或还没到）。补一条 CANCELED，同时给迟到的 Try 留悬挂判据
            mapper.insertControl(xid, branchId, actionName, TccActionStatus.CANCELED.getCode());
            return false;
        }
        if (current == TccActionStatus.TRIED) {
            mapper.updateStatus(xid, branchId,
                    TccActionStatus.TRIED.getCode(), TccActionStatus.CANCELED.getCode());
            return true;
        }
        // CONFIRMED（已提交，忽略 Cancel）/ CANCELED（幂等重试）：跳过
        return false;
    }
}
