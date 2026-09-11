package io.seata.samples.integration.order.dubbo;

import org.apache.seata.core.context.RootContext;
import io.seata.samples.integration.common.dto.OrderDTO;
import io.seata.samples.integration.common.dubbo.OrderDubboService;
import io.seata.samples.integration.common.response.ObjectResponse;
import io.seata.samples.integration.order.service.ITOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @Author: lidong
 * @Description
 * @Date Created in 2019-09-04
 */
// Dubbo 3：application / registry / protocol 三个属性已是 deprecated 且被忽略，
// 应用/协议/注册中心统一由 application.yml 的 dubbo.application / dubbo.protocol / dubbo.registry 提供
@DubboService(version = "1.0.0", timeout = 3000)
@Slf4j
public class OrderDubboServiceImpl implements OrderDubboService {

    @Autowired
    private ITOrderService orderService;

    @Override
    public ObjectResponse<OrderDTO> createOrder(OrderDTO orderDTO) {
        log.info("全局事务id ：" + RootContext.getXID());
        return orderService.createOrder(orderDTO);
    }
}
