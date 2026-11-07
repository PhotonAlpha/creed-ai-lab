package com.creed.parent.dal.repository;

import com.creed.parent.dal.entity.SystemExtraTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/5/24
 */
@Repository
public interface SystemExtraTypesRepository extends JpaRepository<SystemExtraTypes, Long>, JpaSpecificationExecutor<SystemExtraTypes> {
    List<SystemExtraTypes> findByType(String param);
}
