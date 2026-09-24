package com.yumi.catalog.customer.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 客户仓储（模块内部）：重复候选按 name 精确相等 OR phone 精确相等，最多取 5 条、按创建顺序。
 */
public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {

    Optional<CustomerEntity> findByCustomerNo(String customerNo);

    List<CustomerEntity> findTop5ByNameOrderByIdAsc(String name);

    List<CustomerEntity> findTop5ByNameOrPhoneOrderByIdAsc(String name, String phone);

    List<CustomerEntity> findByNameOrderByIdAsc(String name);

    List<CustomerEntity> findByPhoneOrderByIdAsc(String phone);
}
