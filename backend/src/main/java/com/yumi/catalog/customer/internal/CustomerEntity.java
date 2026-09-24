package com.yumi.catalog.customer.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 客户主数据行（V4 customers）：customer_no 唯一、name/phone 允许重复（重复只提示不拦截），
 * 默认收货字段必填，version 乐观锁，审计列由持久化回调与写审计过滤器落。
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_no", nullable = false, columnDefinition = "char(6)")
    private String customerNo;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String contact;

    @Column(length = 30)
    private String phone;

    @Column(length = 1000)
    private String note;

    @Column(name = "default_recipient", nullable = false, length = 100)
    private String defaultRecipient;

    @Column(name = "default_recipient_phone", nullable = false, length = 30)
    private String defaultRecipientPhone;

    @Column(name = "default_region", nullable = false, length = 200)
    private String defaultRegion;

    @Column(name = "default_address", nullable = false, length = 500)
    private String defaultAddress;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) {
            this.version = 0L;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerNo() {
        return customerNo;
    }

    public void setCustomerNo(String customerNo) {
        this.customerNo = customerNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getDefaultRecipient() {
        return defaultRecipient;
    }

    public void setDefaultRecipient(String defaultRecipient) {
        this.defaultRecipient = defaultRecipient;
    }

    public String getDefaultRecipientPhone() {
        return defaultRecipientPhone;
    }

    public void setDefaultRecipientPhone(String defaultRecipientPhone) {
        this.defaultRecipientPhone = defaultRecipientPhone;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    public String getDefaultAddress() {
        return defaultAddress;
    }

    public void setDefaultAddress(String defaultAddress) {
        this.defaultAddress = defaultAddress;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
