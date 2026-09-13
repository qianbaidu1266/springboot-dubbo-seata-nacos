package io.seata.samples.integration.account.mapper;

import io.seata.samples.integration.account.entity.TAccount;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * * @author lidong
 * @since 2019-09-04
 */
public interface TAccountMapper extends BaseMapper<TAccount> {

    /**
     * 减少账户余额（AT 时代的直接扣减，保留给控制器的单服务自测入口使用）
     * @param userId
     * @param amount
     * @return
     */
    int decreaseAccount(@Param("userId") String userId, @Param("amount") Double amount);

    // ==================== 以下为 TCC 模式新增 ====================

    /**
     * Try：冻结（预留）金额。
     * WHERE 里带上可用额校验，余额不足时影响行数为 0，Try 据此失败。
     * 等价于：可用额 = amount - frozen，必须 >= 本次预留额
     */
    int freezeAmount(@Param("userId") String userId, @Param("amount") Double amount);

    /**
     * Confirm：真正扣款并释放冻结额度
     */
    int confirmFreeze(@Param("userId") String userId, @Param("amount") Double amount);

    /**
     * Cancel：解冻（只减 frozen，不动 amount）
     */
    int releaseFreeze(@Param("userId") String userId, @Param("amount") Double amount);
}
