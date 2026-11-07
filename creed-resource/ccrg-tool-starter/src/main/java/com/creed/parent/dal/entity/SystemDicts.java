package com.creed.parent.dal.entity;

import com.creed.parent.constant.CommonStatusEnum;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Table(name = "system_dict", indexes = {
        @Index(name = "CSD_IDX_COMMON", columnList = "name"),
        @Index(name = "CSD_IDX_PID", columnList = "parentId")
})
@Entity
@DynamicUpdate
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = {"node", "zone"})
public class SystemDicts {
    public static final Long PARENT_ID_ROOT = 0L;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long parentId;
    private String name;

    @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JoinTable(name = "system_node_extra_type",
            joinColumns =
                    { @JoinColumn(name = "dict_id", referencedColumnName = "id") },
            inverseJoinColumns =
                    { @JoinColumn(name = "extra_type_id", referencedColumnName = "id") })
    private SystemExtraTypes node;

    @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JoinTable(name = "system_zone_extra_type",
            joinColumns =
                    { @JoinColumn(name = "dict_id", referencedColumnName = "id") },
            inverseJoinColumns =
                    { @JoinColumn(name = "extra_type_id", referencedColumnName = "id") })
    private SystemExtraTypes zone;

    private Integer sort;
    @Column
    @Convert(converter = CommonStatusEnum.Converter.class)
    private CommonStatusEnum status;
}
