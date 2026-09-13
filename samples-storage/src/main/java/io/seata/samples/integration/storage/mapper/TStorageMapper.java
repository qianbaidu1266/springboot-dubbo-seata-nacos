package io.seata.samples.integration.storage.mapper;

import com.baomidou.mybatisplus.mapper.BaseMapper;
import io.seata.samples.integration.storage.entity.TStorage;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * * @author lidong
 * @since 2019-09-04
 */
public interface TStorageMapper extends BaseMapper<TStorage> {

    /**
     * 扣减商品库存
     * @Param: commodityCode 商品code  count扣减数量
     * @Return:
     */
    int decreaseStorage(@Param("commodityCode") String commodityCode, @Param("count") Integer count);

    // ==================== 以下为 TCC 模式新增 ====================

    /**
     * Try：冻结（预留）库存。
     * WHERE 里带可用量校验，可用量 = count - frozen，不足时影响行数为 0，Try 据此失败。
     */
    int freezeStorage(@Param("commodityCode") String commodityCode, @Param("count") Integer count);

    /**
     * Confirm：真正扣减库存并释放冻结额度
     */
    int confirmFreeze(@Param("commodityCode") String commodityCode, @Param("count") Integer count);

    /**
     * Cancel：释放冻结额度（不回滚 count，因为 Try 阶段根本没扣）
     */
    int releaseFreeze(@Param("commodityCode") String commodityCode, @Param("count") Integer count);
}
