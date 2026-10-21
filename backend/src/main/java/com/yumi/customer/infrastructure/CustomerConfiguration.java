package com.yumi.customer.infrastructure;

import com.yumi.customer.api.CustomerApplicationService;
import com.yumi.customer.api.CustomerQuery;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.customer.application.CustomerService;
import com.yumi.customer.application.CustomerSnapshotServiceImpl;
import com.yumi.customer.application.port.CustomerRepository;
import com.yumi.customer.application.port.CustomerSnapshotRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** 客户模块装配：只注册 {@code customer.api} 公开契约。 */
@Configuration
public class CustomerConfiguration {
    @Bean(name = "customerReportingFeed")
    public com.yumi.customer.api.CustomerReportingFeed customerReportingFeed(org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.customer.infrastructure.CustomerReportingFeedImpl(jdbcClient);
    }


    @Bean(name = "customerBeans")
    public CustomerBeans customerBeans(
            CustomerRepository customerRepository,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new CustomerBeans(new CustomerService(
                customerRepository, numbering, authorization, currentActorProvider, clock,
                transactionManager));
    }

    @Bean(name = "customerApplicationService")
    @Primary
    public CustomerApplicationService customerApplicationService(CustomerBeans beans) {
        return beans.service();
    }

    @Bean(name = "customerQuery")
    public CustomerQuery customerQuery(CustomerBeans beans) {
        return beans.service();
    }

    @Bean(name = "customerSnapshotService")
    public CustomerSnapshotService customerSnapshotService(
            CustomerRepository customerRepository,
            CustomerSnapshotRepository customerSnapshotRepository,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new CustomerSnapshotServiceImpl(
                customerRepository, customerSnapshotRepository, authorization, currentActorProvider, clock);
    }

    /** 客户实现持有者（仅供装配）。 */
    public record CustomerBeans(CustomerService service) {
    }
}
