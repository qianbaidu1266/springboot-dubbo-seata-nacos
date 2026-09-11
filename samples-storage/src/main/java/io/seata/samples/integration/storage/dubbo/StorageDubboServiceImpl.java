package io.seata.samples.integration.storage.dubbo;


import org.apache.seata.core.context.RootContext;
import io.seata.samples.integration.common.dto.CommodityDTO;
import io.seata.samples.integration.common.dubbo.StorageDubboService;
import io.seata.samples.integration.common.response.ObjectResponse;
import io.seata.samples.integration.storage.service.ITStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @Author: lidong
 * @Description
 * @Date Created in 2019/1/23 16:13
 */
// Dubbo 3：application / registry / protocol 三个属性已是 deprecated 且被忽略，
// 应用/协议/注册中心统一由 application.yml 的 dubbo.application / dubbo.protocol / dubbo.registry 提供
@DubboService(version = "1.0.0", timeout = 3000)
@Slf4j
public class StorageDubboServiceImpl implements StorageDubboService {

    @Autowired
    private ITStorageService storageService;

    @Override
    public ObjectResponse decreaseStorage(CommodityDTO commodityDTO) {
        log.info("全局事务id ：" + RootContext.getXID());
        return storageService.decreaseStorage(commodityDTO);
    }
}
