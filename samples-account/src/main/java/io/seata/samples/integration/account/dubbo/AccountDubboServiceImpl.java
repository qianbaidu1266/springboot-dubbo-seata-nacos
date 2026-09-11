package io.seata.samples.integration.account.dubbo;

import org.apache.seata.core.context.RootContext;
import io.seata.samples.integration.account.service.ITAccountService;
import io.seata.samples.integration.common.dto.AccountDTO;
import io.seata.samples.integration.common.dubbo.AccountDubboService;
import io.seata.samples.integration.common.response.ObjectResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @Author: lidong
 * @Description  Dubbo Api Impl
 * @Date Created in 2019/1/23 14:40
 */
// Dubbo 3：application / registry / protocol 三个属性已是 deprecated 且被忽略，
// 应用/协议/注册中心统一由 application.yml 的 dubbo.application / dubbo.protocol / dubbo.registry 提供
@DubboService(version = "1.0.0", timeout = 3000)
@Slf4j
public class AccountDubboServiceImpl implements AccountDubboService {

    @Autowired
    private ITAccountService accountService;

    @Override
    public ObjectResponse decreaseAccount(AccountDTO accountDTO) {
        log.info("全局事务id ：" + RootContext.getXID());
        return accountService.decreaseAccount(accountDTO);
    }
}
