package io.seata.samples.integration.common.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * TCC 事务控制表访问接口（幂等 / 空回滚 / 防悬挂的共同支点）。
 *
 * <p>三个 RM 服务（account / storage / order）共用同一个库，因此这里放在
 * {@code samples-common} 中由三方共享，而不是各写一份。</p>
 *
 * <ul>
 *   <li><b>幂等</b>：进入任一阶段先查状态，已处于目标状态就直接返回；</li>
 *   <li><b>空回滚</b>：Cancel 查不到记录 = Try 从未成功，插一条 CANCELED 直接返回；</li>
 *   <li><b>防悬挂</b>：Try 看到自己这条分支已是 CANCELED，说明 Cancel 先到，拒绝执行
 *       —— 空回滚时插入的那条 CANCELED 记录，正是给迟到的 Try 留的判据。</li>
 * </ul>
 *
 * <p>所有写操作必须与业务 SQL 在同一个本地事务里（调用方已加 {@code @Transactional}），
 * 否则会出现"控制表说已回滚、业务数据却留着预留"的撕裂状态。</p>
 *
 * @author lidong
 */
public interface TccTransactionControlMapper {

    /** 查询分支当前状态，无记录返回 null */
    Integer selectStatus(@Param("xid") String xid, @Param("branchId") long branchId);

    /** 插入分支记录（主键 (xid, branch_id)，重复插入抛唯一键冲突） */
    int insertControl(@Param("xid") String xid,
                      @Param("branchId") long branchId,
                      @Param("actionName") String actionName,
                      @Param("status") int status);

    /** 带前置状态的状态流转，影响行数为 0 表示已被并发改过 */
    int updateStatus(@Param("xid") String xid,
                     @Param("branchId") long branchId,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);
}
