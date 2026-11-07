package com.creed.parent.dal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.DynamicUpdate;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Table(name = "system_extra_type", indexes = {
        @Index(name = "SDT_IDX_COMMON", columnList = "type")
})
@Entity
@DynamicUpdate
@Data
@EqualsAndHashCode(callSuper = false)
// @ToString(exclude = {"systemNodeDicts", "systemZoneDicts"})
public class SystemExtraTypes {
    public static final Long PARENT_ID_ROOT = 0L;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String type;
    private String val;

/*     @OneToOne(mappedBy = "node", fetch = FetchType.LAZY, optional = false)
    private SystemDicts systemNodeDicts;


    @OneToOne(mappedBy = "zone", fetch = FetchType.LAZY, optional = false)
    private SystemDicts systemZoneDicts; */
}
