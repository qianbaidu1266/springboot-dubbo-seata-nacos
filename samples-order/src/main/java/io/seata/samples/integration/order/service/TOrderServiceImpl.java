package io.seata.samples.integration.order.service;

import java.util.UUID;


import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import io.seata.samples.integration.common.dto.OrderDTO;
import io.seata.samples.integration.common.enums.RspStatusEnum;
import io.seata.samples.integration.common.response.ObjectResponse;
import io.seata.samples.integration.order.entity.TOrder;
import io.seata.samples.integration.order.mapper.TOrderMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * <p><b>TCC 改造说明</b>：原实现在本地建单前先通过 Dubbo 调用账户服务扣款，那是 AT
 * 模式「一阶段就把业务做掉」的写法；切到 TCC 后，跨服务的资金预留已经上移到
 * {@code OrderTccActionImpl#prepare}（Try）与账户的 Try 里，本类只保留
 * 「单服务自测」用的本地建单能力，不再发起任何跨服务调用。</p>
 *
 * * @author lidong
 * @since 2019-09-04
 */
@Service
public class TOrderServiceImpl extends ServiceImpl<TOrderMapper, TOrder> implements ITOrderService {

    /**
     * 创建订单（仅本地落库，供控制器单服务自测；分布式链路请走 TCC：business → order.prepare → account.prepare）
     */
    @Override
    public ObjectResponse<OrderDTO> createOrder(OrderDTO orderDTO) {
        ObjectResponse<OrderDTO> response = new ObjectResponse<>();
        //生成订单号
        orderDTO.setOrderNo(UUID.randomUUID().toString().replace("-",""));
        //生成订单
        TOrder tOrder = new TOrder();
        BeanUtils.copyProperties(orderDTO,tOrder);
        tOrder.setCount(orderDTO.getOrderCount());
        tOrder.setAmount(orderDTO.getOrderAmount().doubleValue());
        try {
            baseMapper.createOrder(tOrder);
        } catch (Exception e) {
            response.setStatus(RspStatusEnum.FAIL.getCode());
            response.setMessage(RspStatusEnum.FAIL.getMessage());
            return response;
        }

        response.setStatus(RspStatusEnum.SUCCESS.getCode());
        response.setMessage(RspStatusEnum.SUCCESS.getMessage());
        return response;
    }
}
