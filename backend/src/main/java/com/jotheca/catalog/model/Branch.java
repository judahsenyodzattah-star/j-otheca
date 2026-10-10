package com.jotheca.catalog.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A physical branch/campus of the library (spec §18 location model root). */
@Entity
@Table(name = "branches")
public class Branch extends TenantScopedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    /** Short uppercase prefix used when generating copy barcodes, e.g. "AC". */
    @Column(length = 40)
    private String code;

    @Column(length = 300)
    private String address;

    @Column(length = 40)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isMain() { return main; }
    public void setMain(boolean main) { this.main = main; }
}
