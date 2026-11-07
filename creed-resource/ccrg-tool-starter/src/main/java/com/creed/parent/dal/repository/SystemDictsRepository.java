package com.creed.parent.dal.repository;

import com.creed.parent.controller.dict.vo.DictListReqVO;
import com.creed.parent.dal.entity.SystemDicts;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Repository
public interface SystemDictsRepository extends JpaRepository<SystemDicts, Long>, JpaSpecificationExecutor<SystemDicts> {
    Optional<SystemDicts> findByName(String name);
    List<SystemDicts> findByParentId(Long parentId);

    Optional<SystemDicts> findByParentIdAndName(Long parentId, String name);

    boolean existsByParentId(Long id);

    default List<SystemDicts> findByCondition(DictListReqVO reqVO) {
        return findAll(
                (Specification<SystemDicts>) (root, query, cb) -> {
                    List<Predicate> predicateList = new ArrayList<>();
                    if (StringUtils.hasText(reqVO.getName())) {
                        predicateList.add(cb.like(cb.lower(root.get("name").as(String.class)),
                                "%" + reqVO.getName().toLowerCase() + "%"));
                    }
                    if (Objects.nonNull(reqVO.getStatus())) {
                        predicateList.add(cb.equal(root.get("status"), reqVO.getStatus()));
                    }
                    return cb.and(predicateList.toArray(new Predicate[0]));
                }
        );
    }
}
