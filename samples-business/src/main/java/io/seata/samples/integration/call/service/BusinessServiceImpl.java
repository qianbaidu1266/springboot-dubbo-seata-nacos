package io.seata.samples.integration.call.service;

import java.util.UUID;

import org.apache.seata.core.context.RootContext;
import io.seata.samples.integration.common.dubbo.OrderTccAction;
import io.seata.samples.integration.common.dubbo.StorageTccAction;
import io.seata.samples.integration.common.dto.BusinessDTO;
import io.seata.samples.integration.common.enums.RspStatusEnum;
import io.seata.samples.integration.common.response.ObjectResponse;
import org.apache.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

/**
 * @Author: lidong
 * @Description  Dubbo 业务发起方（TM）
 *
 * <p><b>TCC 改造说明</b>：原来调用的是两个「一阶段就把业务做掉」的 Dubbo 接口
 * （{@code decreaseStorage} / {@code createOrder}），现在改调 TCC 的 Try 方法
 * （{@code prepare}）。调用方对 Try 的第一个参数 {@code BusinessActionContext}
 * 一律传 {@code null} —— 真正的分支上下文由 Seata 在<b>提供者侧</b>注入。</p>
 *
 * <p>本类不做真正的数据变更，只负责「开始全局事务 + 依次触发各分支的 Try」；
 * 真正的一/二阶段动作全部发生在 account / storage / order 三个 RM 里。</p>
 *
 * @Date Created in 2019/9/5 18:36
 */
@Service
@Slf4j
public class BusinessServiceImpl implements BusinessService{

    @DubboReference(version = "1.0.0")
    private StorageTccAction storageTccAction;

    @DubboReference(version = "1.0.0")
    private OrderTccAction orderTccAction;

    boolean flag;

    /**
     * 处理业务逻辑 正常的业务逻辑
     * @Param:
     * @Return:
     */
    @GlobalTransactional(timeoutMills = 300000, name = "dubbo-gts-seata-example")
    @Override
    public ObjectResponse handleBusiness(BusinessDTO businessDTO) {
        log.info("开始全局事务，XID = " + RootContext.getXID());
        ObjectResponse<Object> objectResponse = new ObjectResponse<>();

        String orderNo = newOrderNo();

        //1、预留库存（TCC Try）
        storageTccAction.prepare(null, businessDTO.getCommodityCode(), businessDTO.getCount());

        //2、预留账户金额 + 落占位订单（TCC Try；订单的 Try 内部再调账户的 Try）
        orderTccAction.prepare(null, orderNo, businessDTO.getUserId(),
                businessDTO.getCommodityCode(), businessDTO.getCount(),
                businessDTO.getAmount().doubleValue());

        objectResponse.setStatus(RspStatusEnum.SUCCESS.getCode());
        objectResponse.setMessage(RspStatusEnum.SUCCESS.getMessage());
        objectResponse.setData(orderNo);
        return objectResponse;
    }

    /**
     * 出处理业务服务，出现异常回顾
     *
     * @param businessDTO
     * @return
     */
    @GlobalTransactional(timeoutMills = 300000, name = "dubbo-gts-seata-example")
    @Override
    public ObjectResponse handleBusiness2(BusinessDTO businessDTO) {
        log.info("开始全局事务，XID = " + RootContext.getXID());
        ObjectResponse<Object> objectResponse = new ObjectResponse<>();

        String orderNo = newOrderNo();

        //1、预留库存（TCC Try）
        storageTccAction.prepare(null, businessDTO.getCommodityCode(), businessDTO.getCount());

        //2、预留账户金额 + 落占位订单（TCC Try）
        orderTccAction.prepare(null, orderNo, businessDTO.getUserId(),
                businessDTO.getCommodityCode(), businessDTO.getCount(),
                businessDTO.getAmount().doubleValue());

//        打开注释测试事务发生异常后，全局回滚功能
        if (!flag) {
            throw new RuntimeException("测试抛异常后，分布式事务回滚！");
        }

        objectResponse.setStatus(RspStatusEnum.SUCCESS.getCode());
        objectResponse.setMessage(RspStatusEnum.SUCCESS.getMessage());
        objectResponse.setData(orderNo);
        return objectResponse;
    }

    private String newOrderNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
